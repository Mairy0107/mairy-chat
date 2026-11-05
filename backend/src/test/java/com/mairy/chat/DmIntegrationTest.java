package com.mairy.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mairy.chat.service.AuthService;
import com.mairy.chat.util.ChatTime;
import com.mairy.chat.websocket.DmHandshakeInterceptor;
import com.mairy.chat.websocket.DmSocketHandler;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** 真实 H2/REST/事务与推送逻辑回归，仅将网络传输替换为内存连接。 */
@SpringBootTest(properties = {
        "spring.config.import=", "spring.datasource.url=jdbc:h2:mem:dm;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=never" })
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DmIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder encoder;
    @Autowired private AuthService auth;
    @Autowired private DmHandshakeInterceptor handshake;
    @Autowired private DmSocketHandler sockets;
    private String alice;
    private String bob;
    private String carol;
    private String dave;

    /** 使用正式结构与可重复执行升级脚本，账号和数据均仅在内存中。 */
    @BeforeAll
    void schema() throws Exception {
        runSchema("schema.sql");
        runSchema("schema-upgrade-dm.sql");
        runSchema("schema-upgrade-dm.sql");
        String hash = encoder.encode("Password@123");
        jdbc.update("INSERT INTO chat_user(id,public_no,username,nickname,password_hash,role,disabled,created_at) VALUES "
                        + "(101,101,'dm_alice','小艾',?,'USER',FALSE,?),"
                        + "(102,102,'dm_bob','小白',?,'USER',FALSE,?),"
                        + "(103,103,'dm_carol','小陈',?,'USER',FALSE,?),"
                        + "(104,104,'dm_dave','小丁',?,'USER',FALSE,?)",
                hash, ChatTime.now(), hash, ChatTime.now(), hash, ChatTime.now(), hash, ChatTime.now());
        alice = login("dm_alice");
        bob = login("dm_bob");
        carol = login("dm_carol");
        dave = login("dm_dave");
        // 待处理、拒绝与已接受关系同时存在，覆盖好友门禁的全部状态。
        jdbc.update("INSERT INTO chat_friend_request(sender_id,receiver_id,status,created_at) VALUES "
                        + "(101,103,'PENDING',?),(104,101,'REJECTED',?),(102,103,'ACCEPTED',?)",
                ChatTime.now(), ChatTime.now(), ChatTime.now());
    }

    /** 覆盖好友门禁、双向唯一会话、发送与游标分页、跨用户隔离和实时推送。 */
    @Test
    void friendGateHistoryAndRealtime() throws Exception {
        mvc.perform(post("/api/dm/with/102")).andExpect(status().isUnauthorized());
        mvc.perform(body(post("/api/dm/with/102"), alice, null))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("仅好友可私信"));
        mvc.perform(body(post("/api/dm/with/101"), alice, null)).andExpect(status().isForbidden());
        mvc.perform(body(post("/api/dm/with/103"), alice, null)).andExpect(status().isForbidden());
        mvc.perform(body(post("/api/dm/with/104"), alice, null)).andExpect(status().isForbidden());
        mvc.perform(body(post("/api/dm/with/99999"), alice, null)).andExpect(status().isForbidden());
        // 通过正式好友接口同意后才允许打开私信。
        call(post("/api/friends/102"), alice, null);
        long relation = jdbc.queryForObject("SELECT id FROM chat_friend_request WHERE sender_id=101 AND receiver_id=102", Long.class);
        call(post("/api/friends/requests/{id}?accept=true", relation), bob, null);
        JsonNode thread = call(post("/api/dm/with/102"), alice, null);
        String id = thread.path("id").asText();
        assertEquals("102", thread.path("peer").path("id").asText());
        assertFalse(thread.toString().contains("passwordHash"));
        assertEquals(id, call(post("/api/dm/with/101"), bob, null).path("id").asText());
        assertEquals(id, call(post("/api/dm/with/102"), alice, null).path("id").asText());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM chat_dm_thread", Integer.class));
        assertEquals(1, call(get("/api/dm/threads"), alice, null).size());
        assertEquals(0, call(get("/api/dm/threads"), carol, null).size());
        String path = "/api/dm/threads/" + id + "/messages";
        mvc.perform(body(get(path), carol, null)).andExpect(status().isForbidden());
        mvc.perform(body(post(path), carol, Map.of("content", "越权"))).andExpect(status().isForbidden());
        mvc.perform(body(get("/api/dm/threads/999999/messages"), carol, null)).andExpect(status().isForbidden());
        mvc.perform(body(post(path), alice, Map.of("content", "   "))).andExpect(status().isBadRequest());
        mvc.perform(body(post(path), alice, Map.of("content", "x".repeat(1001)))).andExpect(status().isBadRequest());
        mvc.perform(body(get(path + "?size=51"), alice, null)).andExpect(status().isBadRequest());
        mvc.perform(body(get(path + "?beforeId=0"), alice, null)).andExpect(status().isBadRequest());

        WebSocketSession own = socket(alice, "alice-tab");
        WebSocketSession peer = socket(bob, "bob-tab");
        WebSocketSession outsider = socket(carol, "carol-tab");
        try {
            var before = ChatTime.now();
            JsonNode first = call(post(path), alice, Map.of("content", "你好，小白", "senderId", "103", "enc", 1, "iv", "ignored"));
            assertEquals("101", first.path("senderId").asText());
            assertEquals(0, first.path("enc").asInt());
            assertTrue(first.path("iv").isNull());
            assertFalse(java.time.LocalDateTime.parse(first.path("createdAt").asText()).isBefore(before));
            assertEquals("你好，小白", jdbc.queryForObject("SELECT content FROM chat_dm_message WHERE id=?", String.class, first.path("id").asLong()));
            ArgumentCaptor<TextMessage> ownPayload = ArgumentCaptor.forClass(TextMessage.class);
            ArgumentCaptor<TextMessage> peerPayload = ArgumentCaptor.forClass(TextMessage.class);
            verify(own).sendMessage(ownPayload.capture());
            verify(peer).sendMessage(peerPayload.capture());
            assertEquals("dm-message", json.readTree(ownPayload.getValue().getPayload()).path("type").asText());
            assertEquals(first, json.readTree(ownPayload.getValue().getPayload()).path("data"));
            assertEquals(first, json.readTree(peerPayload.getValue().getPayload()).path("data"));
            verify(outsider, never()).sendMessage(any());

            JsonNode second = call(post(path), bob, Map.of("content", "你好，小艾"));
            JsonNode third = call(post(path), alice, Map.of("content", "<script>纯文本</script>"));
            JsonNode latest = call(get(path + "?size=2"), bob, null);
            assertTrue(latest.path("hasMore").asBoolean());
            assertEquals(second.path("id"), latest.path("records").get(0).path("id"));
            assertEquals(third.path("id"), latest.path("records").get(1).path("id"));
            JsonNode older = call(get(path + "?size=2&beforeId=" + latest.path("nextBeforeId").asText()), alice, null);
            assertEquals(first.path("id"), older.path("records").get(0).path("id"));
            assertFalse(older.path("hasMore").asBoolean());

            verifyVoiceMessages(path, own, peer, outsider);

            // 即使会话早已建立，关系变更后仍禁止历史、发送及会话列表。
            jdbc.update("UPDATE chat_friend_request SET status='REJECTED' WHERE id=?", relation);
            mvc.perform(body(get(path), bob, null)).andExpect(status().isForbidden());
            mvc.perform(body(post(path), alice, Map.of("content", "拒绝后发送"))).andExpect(status().isForbidden());
            assertEquals(0, call(get("/api/dm/threads"), alice, null).size());
            jdbc.update("UPDATE chat_friend_request SET status='ACCEPTED' WHERE id=?", relation);
            jdbc.update("UPDATE chat_user SET disabled=TRUE WHERE id=102");
            mvc.perform(body(get(path), bob, null)).andExpect(status().isUnauthorized());
            mvc.perform(body(get(path), alice, null)).andExpect(status().isForbidden());
            mvc.perform(body(post(path), alice, Map.of("content", "停用后发送"))).andExpect(status().isForbidden());
            assertEquals(0, call(get("/api/dm/threads"), alice, null).size());
            jdbc.update("UPDATE chat_user SET disabled=FALSE WHERE id=102");

            // 注销事务提交后立即释放本人连接，其他用户仍可继续发送。
            call(post("/api/auth/logout"), alice, null);
            verify(own).close(CloseStatus.POLICY_VIOLATION);
            clearInvocations(own);
            call(post(path), bob, Map.of("content", "下次见"));
            verify(own, never()).sendMessage(any());
            mvc.perform(body(get(path), alice, null)).andExpect(status().isUnauthorized());
        } finally {
            sockets.afterConnectionClosed(own, CloseStatus.NORMAL);
            sockets.afterConnectionClosed(peer, CloseStatus.NORMAL);
            sockets.afterConnectionClosed(outsider, CloseStatus.NORMAL);
        }
    }

    /** 最大明文语音经过真实 REST、数据库、历史和双方推送，非法载荷不入库。 */
    private void verifyVoiceMessages(String path, WebSocketSession own, WebSocketSession peer,
                                     WebSocketSession outsider) throws Exception {
        clearInvocations(own, peer);
        String audio = java.util.Base64.getEncoder().encodeToString(new byte[512 * 1024]);
        Map<String, Object> valid = Map.of("kind", "voice", "mime", "audio/webm", "duration", 60000,
                "content", audio, "enc", 1, "iv", "ignored");
        JsonNode sent = call(post(path), alice, valid);
        assertEquals("voice", sent.path("kind").asText());
        assertEquals("audio/webm", sent.path("mime").asText());
        assertEquals(60000, sent.path("duration").asInt());
        assertEquals(0, sent.path("enc").asInt());
        assertTrue(sent.path("iv").isNull());
        long messageId = sent.path("id").asLong();
        assertEquals(audio, jdbc.queryForObject("SELECT content FROM chat_dm_message WHERE id=?", String.class, messageId));
        assertEquals(0, jdbc.queryForObject("SELECT enc FROM chat_dm_message WHERE id=?", Integer.class, messageId));
        assertNull(jdbc.queryForObject("SELECT iv FROM chat_dm_message WHERE id=?", String.class, messageId));
        // 数据库 DATETIME 精度与即时回包可能不同，分别核对正文、元数据与时间范围。
        JsonNode historical = call(get(path + "?size=1"), bob, null).path("records").get(0);
        for (String field : java.util.List.of("id", "threadId", "senderId", "content", "kind", "mime", "duration", "enc", "iv")) {
            assertTrue(sent.path(field).equals(historical.path(field)), "历史字段不一致: " + field);
        }
        assertTrue(java.time.Duration.between(java.time.LocalDateTime.parse(sent.path("createdAt").asText()),
                java.time.LocalDateTime.parse(historical.path("createdAt").asText())).abs().toMillis() < 1);
        ArgumentCaptor<TextMessage> payload = ArgumentCaptor.forClass(TextMessage.class);
        verify(own).sendMessage(payload.capture());
        assertEquals(sent, json.readTree(payload.getValue().getPayload()).path("data"));
        verify(peer).sendMessage(any());
        verify(outsider, never()).sendMessage(any());
        mvc.perform(body(post(path), carol, valid)).andExpect(status().isForbidden());

        int count = jdbc.queryForObject("SELECT COUNT(*) FROM chat_dm_message", Integer.class);
        for (Map<String, Object> changes : java.util.List.<Map<String, Object>>of(
                Map.of("kind", "other"), Map.of("mime", "text/html"), Map.of("mime", ""),
                Map.of("duration", 0), Map.of("duration", 60001), Map.of("duration", 1.5),
                Map.of("content", ""), Map.of("content", "Kg"), Map.of("content", "Kh=="),
                Map.of("content", "Kg==\n"), Map.of("content", "not base64"),
                Map.of("content", java.util.Base64.getEncoder().encodeToString(new byte[512 * 1024 + 1])),
                Map.of("content", "A".repeat(699056)))) {
            Map<String, Object> invalid = new HashMap<>(valid);
            invalid.putAll(changes);
            mvc.perform(body(post(path), alice, invalid)).andExpect(status().isBadRequest());
        }
        for (String field : java.util.List.of("mime", "duration")) {
            Map<String, Object> invalid = new HashMap<>(valid);
            invalid.remove(field);
            mvc.perform(body(post(path), alice, invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(body(post(path), alice, Map.of("content", "文字", "mime", "audio/webm")))
                .andExpect(status().isBadRequest());
        mvc.perform(body(post(path), alice, Map.of("content", "文字", "duration", 1)))
                .andExpect(status().isBadRequest());
        assertEquals(count, jdbc.queryForObject("SELECT COUNT(*) FROM chat_dm_message", Integer.class));
        JsonNode minimal = call(post(path), bob, Map.of("kind", "voice", "content", "Kg==",
                "mime", "audio/ogg;codecs=opus", "duration", 1));
        assertEquals(1, minimal.path("duration").asInt());
        JsonNode text = call(post(path), bob, Map.of("content", "兼容原文字"));
        assertEquals("text", text.path("kind").asText());
        assertTrue(text.path("mime").isNull());
        assertTrue(text.path("duration").isNull());
    }

    /** 以 H2 兼容语法执行正式升级定义，验证旧文字与明文约束保留。 */
    @Test
    void voiceMigrationPreservesPlaintextHistory() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(
                "jdbc:h2:mem:dm_voice_migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE chat_dm_message(id BIGINT PRIMARY KEY, content VARCHAR(1000) NOT NULL,"
                    + "enc TINYINT NOT NULL DEFAULT 0, iv VARCHAR(16) NULL, CHECK(enc=0 AND iv IS NULL))");
            statement.execute("INSERT INTO chat_dm_message(id,content) VALUES(1,'旧文字')");
            String sql = new ClassPathResource("schema-upgrade-20261106-dm-voice.sql")
                    .getContentAsString(StandardCharsets.UTF_8).replace("USE mairy_chat;", "")
                    // H2 不支持 MySQL 的多操作 ALTER；仅测试路径拆分语句并移除列位置。
                    .replaceAll(",\\s+ADD COLUMN", "; ALTER TABLE chat_dm_message ADD COLUMN")
                    .replaceAll("(?i)\\s+AFTER\\s+\\w+", "");
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.support.EncodedResource(
                            new org.springframework.core.io.ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
            try (var rows = statement.executeQuery("SELECT * FROM chat_dm_message WHERE id=1")) {
                assertTrue(rows.next());
                assertEquals("旧文字", rows.getString("content"));
                assertEquals("text", rows.getString("kind"));
                assertNull(rows.getObject("mime"));
                assertNull(rows.getObject("duration"));
            }
            statement.execute("INSERT INTO chat_dm_message(id,content,kind,mime,duration) VALUES(2,'"
                    + "A".repeat(699052) + "','voice','audio/webm',60000)");
            assertThrows(java.sql.SQLException.class,
                    () -> statement.execute("UPDATE chat_dm_message SET enc=1 WHERE id=2"));
            assertThrows(java.sql.SQLException.class,
                    () -> statement.execute("UPDATE chat_dm_message SET iv='room-iv' WHERE id=2"));
        }
    }

    /** 独立握手拒绝房间凭证、无凭证、停用及过期登录，也支持 Bearer 请求头。 */
    @Test
    void handshakeUsesActiveLoginBearer() throws Exception {
        assertEquals(401, handshakeStatus("not-a-login-token", null));
        assertEquals(401, handshakeStatus("00000000-0000-0000-0000-000000000001", null));
        assertEquals(401, handshakeStatus(null, null));
        assertEquals(200, handshakeStatus(null, "Bearer " + dave));
        assertEquals(401, handshakeStatus(dave, "Basic invalid"));
        jdbc.update("UPDATE chat_user SET disabled=TRUE WHERE id=104");
        assertEquals(401, handshakeStatus(dave, null));
        jdbc.update("UPDATE chat_user SET disabled=FALSE WHERE id=104");
        jdbc.update("UPDATE chat_login_session SET expires_at=? WHERE user_id=104", ChatTime.now().minusMinutes(1));
        assertEquals(401, handshakeStatus(dave, null));
    }

    /** 连接登记后批量校验可过滤直接停用或删除登录记录的账号。 */
    @Test
    void activeSessionsExcludeDisabledExpiredAndRevoked() {
        var ids = java.util.Set.of(auth.authenticate(carol).session().getId());
        assertEquals(ids, auth.activeSessionIds(ids));
        jdbc.update("UPDATE chat_user SET disabled=TRUE WHERE id=103");
        assertTrue(auth.activeSessionIds(ids).isEmpty());
        jdbc.update("UPDATE chat_user SET disabled=FALSE WHERE id=103");
        assertTrue(auth.activeSessionIds(java.util.Set.of("revoked")).isEmpty());
        assertTrue(auth.activeSessionIds(java.util.Set.of()).isEmpty());
    }

    /** 将 MySQL 建表脚本转为 H2 兼容语法，并显式以 UTF-8 读取中文。 */
    private void runSchema(String resource) throws Exception {
        String sql = new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("(?m)^--.*$", "")
                .replaceAll("(?i)CREATE DATABASE[^;]*;", "").replaceAll("(?i)USE mairy_chat;", "")
                .replace(" STORED", "").replaceAll("(?i)\\) ENGINE=InnoDB[^;]*;", ");");
        try (var connection = jdbc.getDataSource().getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.support.EncodedResource(
                            new org.springframework.core.io.ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        }
    }

    /** 获取真实登录凭证，避免绕过鉴权过滤器。 */
    private String login(String username) throws Exception {
        return call(post("/api/auth/login"), null, Map.of("username", username, "password", "Password@123")).path("token").asText();
    }

    /** 配置请求身份与 JSON，不使用实际数据库或网络配置。 */
    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder builder, String token, Object value) throws Exception {
        builder.with(request -> { request.setRemoteAddr("127.0.0.41"); return request; });
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (value != null) builder.contentType("application/json").content(json.writeValueAsBytes(value));
        return builder;
    }

    /** 调用预期成功的 REST 请求。 */
    private JsonNode call(MockHttpServletRequestBuilder builder, String token, Object value) throws Exception {
        return json.readTree(mvc.perform(body(builder, token, value)).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsByteArray());
    }

    /** 执行正式握手并登记传输替身，后续发布仍经过真实会话校验。 */
    private WebSocketSession socket(String token, String id) throws Exception {
        var request = new MockHttpServletRequest("GET", "/ws/dm");
        request.setQueryString("token=" + token);
        Map<String, Object> attributes = new HashMap<>();
        assertTrue(handshake.beforeHandshake(new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(new MockHttpServletResponse()), sockets, attributes));
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(attributes);
        sockets.afterConnectionEstablished(session);
        return session;
    }

    /** 返回握手状态，不启动真实监听端口。 */
    private int handshakeStatus(String token, String header) {
        var request = new MockHttpServletRequest("GET", "/ws/dm");
        if (token != null) request.setQueryString("token=" + token);
        if (header != null) request.addHeader("Authorization", header);
        var response = new MockHttpServletResponse();
        handshake.beforeHandshake(new ServletServerHttpRequest(request), new ServletServerHttpResponse(response), sockets, new HashMap<>());
        return response.getStatus();
    }
}
