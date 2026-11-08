package com.mairy.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mairy.chat.service.ai.AiModelGateway;
import com.mairy.chat.service.ai.SpringAiChatGateway;
import com.mairy.chat.util.ChatTime;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** 真正加载完整应用及真实网关，禁用环境文件并仅使用 H2。 */
@SpringBootTest(properties = {"spring.config.import=", "chat.ai.api-key=", "chat.ai.provider=spring-ai",
        "spring.datasource.url=jdbc:h2:mem:ai-no-key;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=never"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class AiNoKeyStartupTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder encoder;
    @Autowired private ApplicationContext context;

    /** 无密钥启动后状态和历史可用，发送在占额、保存及网络之前返回 503。 */
    @Test
    void realStartupKeepsHistoryAndRejectsSendWithoutSideEffects() throws Exception {
        assertEquals(1, context.getBeansOfType(AiModelGateway.class).size());
        var gateway = context.getBean(SpringAiChatGateway.class);
        var calls = new AtomicInteger();
        ReflectionTestUtils.setField(gateway, "webClient", WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            return Mono.error(new IllegalStateException("无密钥不得请求网络"));
        }));
        String sql = new ClassPathResource("schema.sql").getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("(?m)^--.*$", "").replaceAll("(?i)CREATE DATABASE[^;]*;", "")
                .replaceAll("(?i)USE mairy_chat;", "").replace(" STORED", "")
                .replaceAll("(?i)\\) ENGINE=InnoDB[^;]*;", ");");
        try (var connection = jdbc.getDataSource().getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        }
        jdbc.update("INSERT INTO chat_user(id,public_no,username,nickname,password_hash,role,disabled,created_at) "
                        + "VALUES(301,301,'ai_no_key','无密钥测试',?,'USER',FALSE,?)", encoder.encode("TestPassword123"), ChatTime.now());
        String token = json.readTree(mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(json.writeValueAsBytes(Map.of("username", "ai_no_key", "password", "TestPassword123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("token").asText();
        mvc.perform(get("/api/ai/status").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        String id = json.readTree(mvc.perform(post("/api/ai/threads").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("id").asText();
        jdbc.update("INSERT INTO chat_ai_message(thread_id,role,content,created_at) VALUES(?,'assistant','历史仍可读',?)", id, ChatTime.now());
        String path = "/api/ai/threads/" + id + "/messages";
        mvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].content").value("历史仍可读"));
        mvc.perform(post(path).header("Authorization", "Bearer " + token).contentType("application/json")
                        .content(json.writeValueAsBytes(Map.of("content", "新问题"))))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("助手未配置"));
        assertEquals(0, calls.get());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM chat_ai_message", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chat_ai_usage_daily", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chat_ai_thread WHERE active_request_id IS NOT NULL", Integer.class));
    }
}
