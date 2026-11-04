package com.mairy.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mairy.chat.dto.ChatViews.MessageView;
import com.mairy.chat.entity.Member;
import com.mairy.chat.entity.Message;
import com.mairy.chat.entity.Room;
import com.mairy.chat.service.MemberService;
import com.mairy.chat.service.AuthService;
import com.mairy.chat.security.AuthIdentity;
import com.mairy.chat.entity.User;
import com.mairy.chat.entity.LoginSession;
import com.mairy.chat.service.MessageService;
import com.mairy.chat.service.RoomService;
import com.mairy.chat.websocket.ChatSocketHandler;
import com.mairy.chat.websocket.SessionHandshakeInterceptor;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** 在 UTC 默认时区下覆盖写入、会话校验与 REST/WebSocket 时间一致性。 */
class TimezoneRegressionTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String TOKEN = "00000000-0000-0000-0000-000000000001";
    private TimeZone originalTimeZone;
    private LocalDateTime startedAt;

    /** 模拟服务器默认 UTC，并记录上海时间的断言下界。 */
    @BeforeEach
    void useUtcDefault() {
        originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        startedAt = LocalDateTime.now(SHANGHAI);
    }

    /** 恢复进程原有时区，避免影响其他测试。 */
    @AfterEach
    void restoreDefaultTimeZone() {
        TimeZone.setDefault(originalTimeZone);
    }

    /** 验证房间写入和返回时间均使用上海时间。 */
    @Test
    void roomCreationUsesShanghaiTime() {
        RoomService service = spy(new RoomService(auth()));
        doAnswer(invocation -> {
            Room room = invocation.getArgument(0);
            room.setId(1L);
            assertShanghaiTime(room.getCreatedAt());
            return true;
        }).when(service).save(any(Room.class));

        assertShanghaiTime(service.createRoom("测试房间").createdAt());
    }

    /** 验证进房写入上海时间，过期时间沿用已验证的八小时登录态。 */
    @Test
    void memberCreationUsesShanghaiTime() {
        AuthService auth = auth();
        MemberService service = spy(new MemberService(mock(RoomService.class), auth));
        doAnswer(invocation -> {
            Member member = invocation.getArgument(0);
            member.setId(2L);
            assertShanghaiTime(member.getJoinedAt());
            assertEquals(auth.current().session().getExpiresAt(), member.getExpiresAt());
            return true;
        }).when(service).save(any(Member.class));

        assertShanghaiTime(service.joinRoom(1L, false).expiresAt().minusHours(8));
    }

    /** 验证消息写入和发送接口结果均使用上海时间。 */
    @Test
    void messageCreationUsesShanghaiTime() {
        MessageService service = messageService();
        doAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            message.setId(3L);
            assertShanghaiTime(message.getCreatedAt());
            return true;
        }).when(service).save(any(Message.class));

        assertShanghaiTime(service.send(1L, TOKEN, encryptedRequest()).createdAt());
    }

    /** 验证 UTC 环境不会将已过期的上海时间会话多放行八小时。 */
    @Test
    void restSessionExpiryUsesShanghaiTime() {
        MemberService service = spy(new MemberService(mock(RoomService.class), auth()));
        LambdaQueryChainWrapper<Member> query = mock(LambdaQueryChainWrapper.class, RETURNS_SELF);
        doReturn(query).when(query).eq(any(), any());
        doReturn(query).when(service).lambdaQuery();
        Member member = member();
        when(query.one()).thenReturn(member);

        assertSame(member, service.requireSession(TOKEN));
        member.setExpiresAt(startedAt.minusMinutes(1));
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.requireSession(TOKEN));
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
    }

    /** 验证实时推送使用相同的上海时间过期规则。 */
    @Test
    void webSocketSessionExpiryUsesShanghaiTime() throws Exception {
        ChatSocketHandler handler = new ChatSocketHandler(new ObjectMapper().findAndRegisterModules(), auth());
        Member member = member();
        member.setExpiresAt(startedAt.minusMinutes(1));
        WebSocketSession session = socket(member);
        handler.afterConnectionEstablished(session);

        handler.publish(1L, messageView(startedAt));

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(session, never()).sendMessage(any());
    }

    /** 使用实际 Jackson 配置验证历史、REST 和 WebSocket 不发生二次时区偏移。 */
    @Test
    void historyRestAndWebSocketKeepTheSameShanghaiTime() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        String zone = yaml.getObject().getProperty("spring.jackson.time-zone", "UTC");
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withPropertyValues("spring.jackson.time-zone=" + zone)
                .run(context -> {
                    ObjectMapper mapper = context.getBean(ObjectMapper.class);
                    assertEquals("Asia/Shanghai", mapper.getSerializationConfig().getTimeZone().getID());
                    MessageService service = messageService();
                    Message saved = new Message();
                    doAnswer(invocation -> {
                        Message message = invocation.getArgument(0);
                        message.setId(3L);
                        saved.setId(message.getId());
                        saved.setRoomId(message.getRoomId());
                        saved.setMemberId(message.getMemberId());
                        saved.setNickname(message.getNickname());
                        saved.setContent(message.getContent());
                        saved.setEnc(message.getEnc());
                        saved.setIv(message.getIv());
                        saved.setCreatedAt(message.getCreatedAt());
                        return true;
                    }).when(service).save(any(Message.class));
                    MessageView sent = service.send(1L, TOKEN, encryptedRequest());
                    assertShanghaiTime(sent.createdAt());
                    LambdaQueryChainWrapper<Message> query = mock(LambdaQueryChainWrapper.class, RETURNS_SELF);
                    doReturn(query).when(query).eq(any(), any());
                    doReturn(query).when(query).lt(anyBoolean(), any(), any());
                    doReturn(query).when(query).orderByDesc(any(SFunction.class));
                    doReturn(query).when(service).lambdaQuery();
                    when(query.page(any(Page.class))).thenReturn(new Page<Message>().setRecords(List.of(saved)));
                    MessageView history = service.history(1L, TOKEN, null, 30).records().get(0);
                    assertEquals(sent, history);

                    MockHttpOutputMessage output = new MockHttpOutputMessage();
                    new MappingJackson2HttpMessageConverter(mapper).write(sent, null, output);
                    var rest = mapper.readTree(output.getBodyAsString());
                    assertEquals(sent.createdAt(), LocalDateTime.parse(rest.get("createdAt").asText()));
                    assertEquals(rest, mapper.readTree(mapper.writeValueAsString(history)));

                    ChatSocketHandler handler = new ChatSocketHandler(mapper, auth());
                    WebSocketSession session = socket(member());
                    handler.afterConnectionEstablished(session);
                    handler.publish(1L, sent);
                    ArgumentCaptor<TextMessage> payload = ArgumentCaptor.forClass(TextMessage.class);
                    verify(session).sendMessage(payload.capture());
                    assertEquals(rest, mapper.readTree(payload.getValue().getPayload()).get("data"));
                    assertFalse(rest.get("createdAt").asText().endsWith("Z"));
                });
    }

    /** 断言时间落在当前测试期间的上海时间范围内。 */
    private void assertShanghaiTime(LocalDateTime actual) {
        assertTrue(!actual.isBefore(startedAt) && !actual.isAfter(LocalDateTime.now(SHANGHAI)),
                () -> "应为上海时间，实际为 " + actual + "，测试开始时间为 " + startedAt);
    }

    /** 构造只替换身份和持久化边界的真实消息服务。 */
    private MessageService messageService() {
        MemberService members = mock(MemberService.class);
        when(members.requireRoomSession(1L, TOKEN)).thenReturn(member());
        return spy(new MessageService(members, mock(com.mairy.chat.service.E2eeService.class)));
    }

    /** 构造仍有效的上海时间会话。 */
    private Member member() {
        Member member = new Member();
        member.setId(2L);
        member.setUserId(1L);
        member.setLoginSessionId("login-session");
        member.setRoomId(1L);
        member.setNickname("测试员");
        member.setExpiresAt(startedAt.plusHours(1));
        return member;
    }

    /** 构造连接边界替身，推送和过期判断仍执行真实逻辑。 */
    private WebSocketSession socket(Member member) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("test-session");
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(Map.of(SessionHandshakeInterceptor.MEMBER_ATTRIBUTE, member));
        return session;
    }

    /** 构造已验证登录身份，仍在UTC环境使用上海时间。 */
    private AuthService auth() {
        AuthService service = mock(AuthService.class);
        User user = new User();
        user.setId(1L);
        user.setNickname("测试员");
        user.setRole("USER");
        LoginSession login = new LoginSession();
        login.setId("login-session");
        login.setExpiresAt(com.mairy.chat.util.ChatTime.now().plusHours(8));
        AuthIdentity identity = new AuthIdentity(user, login);
        when(service.current()).thenReturn(identity);
        when(service.requireActiveSession("login-session")).thenReturn(identity);
        return service;
    }

    /** 使用文字测试密文验证类型、封装字段和上海时间保持一致。 */
    private com.mairy.chat.dto.ChatRequests.SendMessage encryptedRequest() {
        return new com.mairy.chat.dto.ChatRequests.SendMessage(
                java.util.Base64.getEncoder().encodeToString(new byte[20]), 1,
                java.util.Base64.getEncoder().encodeToString(new byte[12]), "text");
    }

    /** 构造带文字类型及指定时间的消息推送数据。 */
    private MessageView messageView(LocalDateTime createdAt) {
        return new MessageView("3", "1", "2", "测试员", "你好", 0, null, createdAt, "text");
    }
}
