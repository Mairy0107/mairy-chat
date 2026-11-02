package com.mairy.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mairy.chat.dto.AuthRequests.Register;
import com.mairy.chat.service.AuthService;
import com.mairy.chat.service.IpSecurityService;
import com.mairy.chat.util.ChatTime;
import com.mairy.chat.websocket.ChatSocketHandler;
import com.mairy.chat.websocket.SessionHandshakeInterceptor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/** 仅用内存H2验证真实控制器、事务与约束，绝不连接.env中的数据库。 */
@SpringBootTest(properties = {
        "spring.config.import=", "spring.datasource.url=jdbc:h2:mem:auth;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=never" })
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthService auth;
    @Autowired private PasswordEncoder encoder;
    @Autowired private IpSecurityService ips;
    @Autowired private SessionHandshakeInterceptor handshake;
    @Autowired private ChatSocketHandler sockets;

    /** 使用正式建表脚本，仅移除H2不支持的建库与MySQL存储选项。 */
    @BeforeAll
    void schema() throws Exception {
        String sql = new ClassPathResource("schema.sql").getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("(?m)^--.*$", "")
                .replaceAll("(?i)CREATE DATABASE[^;]*;", "").replaceAll("(?i)USE mairy_chat;", "")
                .replace(" STORED", "").replaceAll("(?i)\\) ENGINE=InnoDB[^;]*;", ");");
        try (var connection = jdbc.getDataSource().getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        }
        String seed = new ClassPathResource("data-local.sql").getContentAsString(StandardCharsets.UTF_8);
        var hash = java.util.regex.Pattern.compile("\\$2a\\$10\\$[./A-Za-z0-9]{53}").matcher(seed);
        assertTrue(hash.find());
        assertTrue(encoder.matches("ChangeMe@123", hash.group()));
        jdbc.update("INSERT INTO chat_user(public_no,username,nickname,password_hash,role,disabled,created_at) VALUES(1,'admin','管理员',?,'ADMIN',FALSE,?)",
                hash.group(), ChatTime.now());
    }

    /** 覆盖真实注册登录、编号、好友、单房规则、隐身权限与重置撤销。 */
    @Test
    @org.junit.jupiter.api.Order(1)
    void accountRoomFriendAndResetFlow() throws Exception {
        String ip = "127.0.0.2";
        mvc.perform(get("/api/rooms")).andExpect(status().isUnauthorized());
        JsonNode first = call(post("/api/auth/register"), ip, null, registration("alice", "a".repeat(64)));
        JsonNode second = call(post("/api/auth/register"), ip, null, registration("bob", "a".repeat(64)));
        assertEquals("#0002", first.path("publicNo").asText());
        call(post("/api/auth/register"), ip, null, registration("carol", "a".repeat(64)));
        mvc.perform(body(post("/api/auth/register"), ip, null, registration("dave", "a".repeat(64))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("该浏览器设备最多注册3个账号"));
        String alice = login(ip, "alice");
        String bob = login(ip, "bob");
        String admin = login(ip, "admin");
        mvc.perform(body(get("/api/admin/users"), ip, alice, null)).andExpect(status().isForbidden());
        JsonNode profiles = call(get("/api/admin/users"), ip, admin, null);
        assertFalse(profiles.toString().contains("passwordHash"));
        JsonNode room = call(post("/api/rooms"), ip, alice, Map.of("name", "测试房间"));
        String roomId = room.path("id").asText();
        mvc.perform(body(post("/api/rooms"), ip, alice, Map.of("name", "第二间"))).andExpect(status().isConflict());
        mvc.perform(body(post("/api/rooms/{id}/members", roomId), ip, alice, Map.of("stealth", true)))
                .andExpect(status().isForbidden());
        JsonNode member = call(post("/api/rooms/{id}/members", roomId), ip, alice, Map.of("stealth", false));
        String roomToken = member.path("token").asText();
        mvc.perform(body(get("/api/rooms/{id}/messages", roomId).header("X-Session-Token", roomToken), ip, bob, null))
                .andExpect(status().isForbidden());
        call(post("/api/rooms/{id}/messages", roomId).header("X-Session-Token", roomToken), ip, alice, Map.of("content", "你好😀"));
        JsonNode invisible = call(post("/api/rooms/{id}/members", roomId), ip, admin, Map.of("stealth", true));
        mvc.perform(body(post("/api/rooms/{id}/messages", roomId).header("X-Session-Token", invisible.path("token").asText()),
                ip, admin, Map.of("content", "隐身发送"))).andExpect(status().isForbidden());
        assertEquals(1, call(get("/api/friends/search").param("q", "#0003"), ip, alice, null).size());
        call(post("/api/friends/{id}", second.path("id").asText()), ip, alice, null);
        mvc.perform(body(post("/api/friends/{id}", first.path("id").asText()), ip, bob, null)).andExpect(status().isConflict());
        String requestId = call(get("/api/friends"), ip, bob, null).get(0).path("id").asText();
        mvc.perform(body(post("/api/friends/requests/{id}", requestId).param("accept", "true"), ip, alice, null)).andExpect(status().isConflict());
        call(post("/api/friends/requests/{id}", requestId).param("accept", "true"), ip, bob, null);
        assertEquals("ACCEPTED", call(get("/api/friends"), ip, alice, null).get(0).path("status").asText());
        call(post("/api/auth/forgot-password"), ip, null, Map.of("username", "alice", "note", "人工核实"));
        assertEquals(1, call(get("/api/admin/reset-requests"), ip, admin, null).size());
        Map<String, Object> attributes = new HashMap<>();
        assertTrue(handshake(roomToken, attributes));
        WebSocketSession socket = org.mockito.Mockito.mock(WebSocketSession.class);
        org.mockito.Mockito.when(socket.getId()).thenReturn("integration-socket");
        org.mockito.Mockito.when(socket.isOpen()).thenReturn(true);
        org.mockito.Mockito.when(socket.getAttributes()).thenReturn(attributes);
        sockets.afterConnectionEstablished(socket);
        call(post("/api/admin/users/{id}/reset-password", first.path("id").asText()), ip, admin, Map.of("password", "Temporary@123"));
        org.mockito.Mockito.verify(socket).close(CloseStatus.POLICY_VIOLATION);
        mvc.perform(body(get("/api/auth/me"), ip, alice, null)).andExpect(status().isUnauthorized());
        assertFalse(handshake(roomToken, new HashMap<>()));
        // 模拟握手成功后发生重置，旧连接不能在撤销通知后迟到登记。
        WebSocketSession lateSocket = org.mockito.Mockito.mock(WebSocketSession.class);
        org.mockito.Mockito.when(lateSocket.getId()).thenReturn("late-socket");
        org.mockito.Mockito.when(lateSocket.getAttributes()).thenReturn(attributes);
        sockets.afterConnectionEstablished(lateSocket);
        org.mockito.Mockito.verify(lateSocket).close(CloseStatus.POLICY_VIOLATION);
        String fresh = call(post("/api/auth/login"), ip, null, Map.of("username", "alice", "password", "Temporary@123")).path("token").asText();
        call(post("/api/auth/logout"), ip, fresh, null);
        mvc.perform(body(get("/api/auth/me"), ip, fresh, null)).andExpect(status().isUnauthorized());
    }

    /** 四个并发注册共享设备时只能提交三个账号且编号不重复。 */
    @Test
    @org.junit.jupiter.api.Order(2)
    void concurrentDeviceQuota() throws Exception {
        var executor = Executors.newFixedThreadPool(4);
        var start = new CountDownLatch(1);
        try {
            var tasks = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 4; i++) {
                final int number = i;
                tasks.add(executor.submit(() -> {
                    start.await();
                    try {
                        auth.register(new Register("parallel" + number, "Password@123", "并发用户", "b".repeat(64)), "Windows Chrome/1");
                        return true;
                    } catch (ResponseStatusException exception) {
                        assertEquals(409, exception.getStatusCode().value());
                        return false;
                    }
                }));
            }
            start.countDown();
            int succeeded = 0;
            for (var task : tasks) if (task.get(30, TimeUnit.SECONDS)) succeeded++;
            assertEquals(3, succeeded);
        } finally { executor.shutdownNow(); }
    }

    /** 并发找回申请通过账号行锁合并为一条待办。 */
    @Test
    @org.junit.jupiter.api.Order(3)
    void concurrentResetApplications() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> auth.requestReset("bob", "申请一"));
            var second = executor.submit(() -> auth.requestReset("bob", "申请二"));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM chat_password_reset_request r JOIN chat_user u ON u.id=r.user_id WHERE u.username='bob' AND r.status='PENDING'", Integer.class));
        } finally { executor.shutdownNow(); }
    }

    /** 错误登录封禁落库且管理员可解封；伪造代理头不会改变限流IP。 */
    @Test
    @org.junit.jupiter.api.Order(4)
    void failedLoginBlockAndUnblock() throws Exception {
        String ip = "127.0.0.9";
        for (int i = 0; i < 5; i++) {
            mvc.perform(body(post("/api/auth/login").header("X-Forwarded-For", "10.0.0." + i), ip, null,
                    Map.of("username", "missing", "password", "bad"))).andExpect(status().isUnauthorized());
        }
        mvc.perform(body(post("/api/auth/login"), ip, null, Map.of("username", "admin", "password", "ChangeMe@123")))
                .andExpect(status().isTooManyRequests());
        assertEquals(1, ips.listBlocks().size());
        String admin = login("127.0.0.10", "admin");
        call(post("/api/admin/ip-blocks/unblock").param("ip", ip), "127.0.0.10", admin, null);
        login(ip, "admin");
        mvc.perform(options("/api/rooms").header("Origin", "http://evil.example")
                .header("Access-Control-Request-Method", "GET")).andExpect(status().isForbidden());
    }

    /** 构造统一的正常注册请求。 */
    private Map<String, String> registration(String username, String fingerprint) {
        return Map.of("username", username, "nickname", username, "password", "Password@123", "machineFingerprint", fingerprint);
    }

    /** 使用测试账号密码登录，管理员使用本地种子密码。 */
    private String login(String ip, String username) throws Exception {
        return call(post("/api/auth/login"), ip, null, Map.of("username", username,
                "password", username.equals("admin") ? "ChangeMe@123" : "Password@123")).path("token").asText();
    }

    /** 构造真实HTTP边界并显式设置直连测试IP。 */
    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder builder, String ip, String token, Object value) throws Exception {
        builder.with(request -> { request.setRemoteAddr(ip); return request; });
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (value != null) builder.contentType("application/json").content(json.writeValueAsBytes(value));
        return builder;
    }

    /** 调用应成功的业务接口并读取响应。 */
    private JsonNode call(MockHttpServletRequestBuilder builder, String ip, String token, Object value) throws Exception {
        return json.readTree(mvc.perform(body(builder, ip, token, value)).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsByteArray());
    }

    /** 直接经过正式握手拦截器校验登录绑定的进房凭证。 */
    private boolean handshake(String token, Map<String, Object> attributes) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/chat");
        request.setQueryString("token=" + token);
        return handshake.beforeHandshake(new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(new MockHttpServletResponse()), sockets, attributes);
    }
}
