package com.mairy.chat.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mairy.chat.security.AuthIdentity;
import com.mairy.chat.security.SessionRevoked;
import com.mairy.chat.service.AuthService;
import com.mairy.chat.service.DmMessageSent;
import com.mairy.chat.util.ChatTime;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** 按账号索引所有私信连接，只向指定会话双方推送。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DmSocketHandler extends TextWebSocketHandler {
    private final ObjectMapper objectMapper;
    private final AuthService auth;
    private final Map<Long, Map<String, WebSocketSession>> users = new ConcurrentHashMap<>();

    /** 登记前复核登录；1 MiB 慢连接缓冲容纳单条语音，仍限制发送5秒。 */
    @Override
    public synchronized void afterConnectionEstablished(WebSocketSession session) {
        AuthIdentity identity = identity(session);
        try {
            auth.requireActiveSession(identity.session().getId());
        } catch (ResponseStatusException | DataAccessException exception) {
            disconnect(session);
            return;
        }
        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(session, 5000, 1024 * 1024);
        users.computeIfAbsent(identity.user().getId(), id -> new ConcurrentHashMap<>())
                .put(session.getId(), safeSession);
    }

    /** 私信发送只能经过 REST 的成员和好友校验。 */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        session.close(CloseStatus.POLICY_VIOLATION.withReason("Use REST to send messages"));
    }

    /** 提交后批量复核双方连接的登录态，循环内仅做内存检查与发送。 */
    @TransactionalEventListener
    public synchronized void publish(DmMessageSent event) {
        List<WebSocketSession> sockets = new ArrayList<>();
        sockets.addAll(users.getOrDefault(event.userLow(), Map.of()).values());
        sockets.addAll(users.getOrDefault(event.userHigh(), Map.of()).values());
        if (sockets.isEmpty()) return;
        final TextMessage payload;
        final Set<String> active;
        try {
            active = auth.activeSessionIds(sockets.stream().map(socket -> identity(socket).session().getId())
                    .collect(Collectors.toSet()));
            payload = new TextMessage(objectMapper.writeValueAsString(Map.of("type", "dm-message", "data", event.message())));
        } catch (JsonProcessingException | DataAccessException exception) {
            // 消息已入库，推送失败不影响发送回包，也不记录消息内容或凭证。
            log.warn("私信推送暂不可用，客户端可通过历史补读");
            return;
        }
        for (WebSocketSession session : sockets) {
            try {
                if (!session.isOpen() || !active.contains(identity(session).session().getId())
                        || !identity(session).session().getExpiresAt().isAfter(ChatTime.now())) {
                    disconnect(session);
                    continue;
                }
                session.sendMessage(payload);
            } catch (IOException | RuntimeException exception) {
                disconnect(session);
            }
        }
    }

    /** 注销或重置密码提交后立即关闭对应连接，不在循环中查询。 */
    @TransactionalEventListener(fallbackExecution = true)
    public synchronized void revoke(SessionRevoked event) {
        for (Map<String, WebSocketSession> sockets : users.values()) {
            for (WebSocketSession session : sockets.values()) {
                AuthIdentity identity = identity(session);
                if ((event.userId() != null && event.userId().equals(identity.user().getId()))
                        || (event.sessionId() != null && event.sessionId().equals(identity.session().getId()))) {
                    disconnect(session);
                }
            }
        }
    }

    /** 定期按上海时间关闭到期连接。 */
    @Scheduled(fixedDelay = 30000)
    public void expireConnections() {
        for (Map<String, WebSocketSession> sockets : users.values()) {
            for (WebSocketSession session : sockets.values()) {
                if (!identity(session).session().getExpiresAt().isAfter(ChatTime.now())) disconnect(session);
            }
        }
    }

    /** 清理账号连接索引与空集合。 */
    @Override
    public synchronized void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        users.computeIfPresent(identity(session).user().getId(), (id, sockets) -> {
            sockets.remove(session.getId());
            return sockets.isEmpty() ? null : sockets;
        });
    }

    /** 网络异常主动释放连接。 */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        disconnect(session);
    }

    /** 只读取握手时服务端写入的身份。 */
    private AuthIdentity identity(WebSocketSession session) {
        return (AuthIdentity) session.getAttributes().get(DmHandshakeInterceptor.IDENTITY_ATTRIBUTE);
    }

    /** 关闭异常连接，即使关闭失败也清理索引。 */
    private void disconnect(WebSocketSession session) {
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException | RuntimeException exception) {
            log.debug("私信连接已失效");
        } finally {
            afterConnectionClosed(session, CloseStatus.POLICY_VIOLATION);
        }
    }
}
