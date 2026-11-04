package com.mairy.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mairy.chat.config.AiProperties;
import com.mairy.chat.mapper.AiUsageMapper;
import com.mairy.chat.service.ai.DeepSeekClient;
import com.mairy.chat.service.ai.SensitiveWordFilter;
import com.mairy.chat.util.ChatTime;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 真实鉴权、H2和短事务，只替换模型网络，不读取本机环境文件。 */
@SpringBootTest(properties = {
        "spring.config.import=", "spring.datasource.url=jdbc:h2:mem:ai;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=never", "chat.ai.api-key=test-placeholder", "chat.ai.daily-quota=2",
        "chat.ai.sensitive-words[0]=测试拦截词" })
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder encoder;
    @Autowired private AiProperties properties;
    @Autowired private AiUsageMapper usage;
    @MockitoBean private DeepSeekClient client;
    private String alice;
    private String bob;

    /** 正式 schema 和升级脚本可重复执行，测试账号全部只在内存中。 */
    @BeforeAll
    void schema() throws Exception {
        runSchema("schema.sql");
        runSchema("schema-upgrade-ai.sql");
        runSchema("schema-upgrade-ai.sql");
        String hash = encoder.encode("TestPassword123");
        jdbc.update("INSERT INTO chat_user(id,public_no,username,nickname,password_hash,role,disabled,created_at) "
                        + "VALUES(201,201,'ai_alice','小艾',?,'USER',FALSE,?),(202,202,'ai_bob','小白',?,'USER',FALSE,?)",
                hash, ChatTime.now(), hash, ChatTime.now());
        alice = login("ai_alice");
        bob = login("ai_bob");
    }

    /** 各测试使用独立业务数据，默认模型返回两段文本。 */
    @BeforeEach
    void resetData() throws Exception {
        jdbc.update("DELETE FROM chat_ai_message");
        jdbc.update("DELETE FROM chat_ai_thread");
        jdbc.update("DELETE FROM chat_ai_usage_daily");
        reset(client);
        doAnswer(invocation -> {
            DeepSeekClient.DeltaConsumer out = invocation.getArgument(1);
            out.accept("你好，");
            out.accept("有什么可以帮你？");
            return null;
        }).when(client).stream(any(), any());
    }

    /** Bearer、归属隔离、游标和输入校验均在 SSE 提交前执行。 */
    @Test
    void ownershipHistoryAndQuota() throws Exception {
        mvc.perform(get("/api/ai/status")).andExpect(status().isUnauthorized());
        assertEquals(2, call(get("/api/ai/status"), alice, null).path("quotaRemaining").asInt());
        assertFalse(call(get("/api/ai/status"), alice, null).path("profileCompleted").asBoolean());
        String id = call(post("/api/ai/threads"), alice, null).path("id").asText();
        String path = "/api/ai/threads/" + id + "/messages";
        mvc.perform(body(get(path), bob, null)).andExpect(status().isForbidden());
        mvc.perform(body(post(path), bob, Map.of("content", "越权"))).andExpect(status().isForbidden());
        assertEquals(0, call(get("/api/ai/threads"), bob, null).size());
        mvc.perform(body(post(path), alice, Map.of("content", "  "))).andExpect(status().isBadRequest());
        mvc.perform(body(post(path), alice, Map.of("content", "字".repeat(2001)))).andExpect(status().isBadRequest());
        mvc.perform(body(get(path + "?size=51"), alice, null)).andExpect(status().isBadRequest());

        String stream = send(path, "你好");
        assertTrue(stream.contains("event: delta"));
        assertTrue(stream.contains("event: done"));
        assertFalse(stream.contains("activeRequestId"));
        send(path, "再聊聊");
        mvc.perform(body(post(path), alice, Map.of("content", "超额"))).andExpect(status().isTooManyRequests());
        verify(client, times(2)).stream(any(), any());
        JsonNode latest = call(get(path + "?size=2"), alice, null);
        assertTrue(latest.path("hasMore").asBoolean());
        JsonNode older = call(get(path + "?size=2&beforeId=" + latest.path("nextBeforeId").asText()), alice, null);
        assertEquals(2, older.path("records").size());
        assertFalse(older.path("hasMore").asBoolean());
        assertEquals("user", older.path("records").get(0).path("role").asText());
        assertEquals("你好，有什么可以帮你？", older.path("records").get(1).path("content").asText());
        assertEquals(2, usage.count(201L, ChatTime.now().toLocalDate()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chat_ai_thread WHERE busy_until IS NOT NULL", Integer.class));
    }

    /** 未配置和输入过滤不占额度，跨分片输出被替换后只保存安全提示。 */
    @Test
    void missingKeyAndSensitiveInputOutput() throws Exception {
        String id = call(post("/api/ai/threads"), alice, null).path("id").asText();
        String path = "/api/ai/threads/" + id + "/messages";
        properties.setApiKey("");
        try {
            mvc.perform(body(post(path), alice, Map.of("content", "你好")))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("助手未配置"));
        } finally { properties.setApiKey("test-placeholder"); }
        mvc.perform(body(post(path), alice, Map.of("content", "这包含测试拦截词"))).andExpect(status().isBadRequest());
        assertNull(usage.count(201L, ChatTime.now().toLocalDate()));
        verifyNoInteractions(client);
        doAnswer(invocation -> {
            DeepSeekClient.DeltaConsumer out = invocation.getArgument(1);
            out.accept("正常说明。测试");
            out.accept("拦截");
            out.accept("词");
            return null;
        }).when(client).stream(any(), any());
        String stream = send(path, "解释一下");
        assertTrue(stream.contains("event: replace"));
        assertTrue(stream.contains(SensitiveWordFilter.REFUSAL));
        assertFalse(stream.contains("测试"));
        assertEquals(SensitiveWordFilter.REFUSAL, call(get(path), alice, null)
                .path("records").get(1).path("content").asText());
    }

    /** 上游失败不能泄漏异常正文，租约释放后能够继续发送。 */
    @Test
    void upstreamFailureAndBusyLease() throws Exception {
        String id = call(post("/api/ai/threads"), alice, null).path("id").asText();
        String path = "/api/ai/threads/" + id + "/messages";
        jdbc.update("UPDATE chat_ai_thread SET busy_until=? WHERE id=?", ChatTime.now().plusMinutes(1), id);
        mvc.perform(body(post(path), alice, Map.of("content", "并发"))).andExpect(status().isConflict());
        assertNull(usage.count(201L, ChatTime.now().toLocalDate()));
        jdbc.update("UPDATE chat_ai_thread SET busy_until=? WHERE id=?", ChatTime.now().minusMinutes(1), id);
        doThrow(new IllegalStateException("不可回显上游正文")).when(client).stream(any(), any());
        String stream = send(path, "失败测试");
        assertTrue(stream.contains("event: done"));
        assertFalse(stream.contains("不可回显上游正文"));
        assertEquals(1, usage.count(201L, ChatTime.now().toLocalDate()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chat_ai_thread WHERE busy_until IS NOT NULL", Integer.class));
    }

    /** 多线程竞争同一自然日计数只能成功两次，昨日计数不影响今日。 */
    @Test
    void quotaIsAtomicAndDateScoped() throws Exception {
        var today = ChatTime.now().toLocalDate();
        usage.ensure(201L, today.minusDays(1));
        assertEquals(1, usage.consume(201L, today.minusDays(1), 2));
        usage.ensure(201L, today);
        var pool = Executors.newFixedThreadPool(6);
        try {
            var tasks = new ArrayList<java.util.concurrent.Callable<Integer>>();
            for (int i = 0; i < 12; i++) tasks.add(() -> usage.consume(201L, today, 2));
            int accepted = 0;
            for (var future : pool.invokeAll(tasks)) accepted += future.get();
            assertEquals(2, accepted);
            assertEquals(2, usage.count(201L, today));
            assertEquals(1, usage.count(201L, today.minusDays(1)));
        } finally { pool.shutdownNow(); }
    }

    /** 兼容既有 H2 测试的 MySQL schema 转换方式。 */
    private void runSchema(String resource) throws Exception {
        String sql = new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("(?m)^--.*$", "").replaceAll("(?i)CREATE DATABASE[^;]*;", "")
                .replaceAll("(?i)USE mairy_chat;", "").replace(" STORED", "")
                .replaceAll("(?i)\\) ENGINE=InnoDB[^;]*;", ");");
        try (var connection = jdbc.getDataSource().getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** 登录真实测试账号，不跳过鉴权过滤器。 */
    private String login(String username) throws Exception {
        return call(post("/api/auth/login"), null, Map.of("username", username, "password", "TestPassword123"))
                .path("token").asText();
    }

    /** 固定测试来源并携带 JSON 与登录凭证，不输出请求正文。 */
    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder builder, String token, Object value) throws Exception {
        builder.with(request -> { request.setRemoteAddr("127.0.0.51"); return request; });
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (value != null) builder.contentType("application/json").content(json.writeValueAsBytes(value));
        return builder;
    }

    /** 读取成功的常规 JSON 响应。 */
    private JsonNode call(MockHttpServletRequestBuilder builder, String token, Object value) throws Exception {
        return json.readTree(mvc.perform(body(builder, token, value)).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsByteArray());
    }

    /** 同步 MVC 流在测试响应中保留帧边界。 */
    private String send(String path, String content) throws Exception {
        return mvc.perform(body(post(path), alice, Map.of("content", content))).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
