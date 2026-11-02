package com.mairy.chat.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mairy.chat.dto.ChatViews.MessageView;
import com.mairy.chat.entity.Member;
import com.mairy.chat.util.ChatTime;
import com.mairy.chat.security.SessionRevoked;
import com.mairy.chat.service.AuthService;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.scheduling.annotation.Scheduled;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatSocketHandler extends TextWebSocketHandler {
    private final ObjectMapper objectMapper;
    private final AuthService auth;
    private final Map<Long, Map<String, WebSocketSession>> rooms = new ConcurrentHashMap<>();

    /** 按服务端会话分组，装饰器限制慢连接并发发送。 */
    @Override
    public synchronized void afterConnectionEstablished(WebSocketSession session) {
        Member member = (Member) session.getAttributes().get(SessionHandshakeInterceptor.MEMBER_ATTRIBUTE);
        // 与撤销事件共用锁并再校验一次，封住握手后、登记前的退出竞态。
        try {
            auth.requireActiveSession(member.getLoginSessionId());
        } catch (ResponseStatusException | DataAccessException exception) {
            disconnect(session);
            return;
        }
        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(session, 5000, 65536);
        rooms.compute(member.getRoomId(), (roomId, sockets) -> {
            Map<String, WebSocketSession> result = sockets == null ? new ConcurrentHashMap<>() : sockets;
            result.put(session.getId(), safeSession);
            return result;
        });
    }

    /** 实时通道只推送消息，发送操作统一走带校验的 REST 接口。 */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        session.close(CloseStatus.POLICY_VIOLATION.withReason("Use REST to send messages"));
    }

    /** 按上海时间校验会话并推送已提交消息，循环中不访问数据库。 */
    public void publish(Long roomId, MessageView message) {
        Map<String, WebSocketSession> sockets = rooms.get(roomId);
        if (sockets == null) {
            return;
        }
        final TextMessage payload;
        try {
            payload = new TextMessage(objectMapper.writeValueAsString(Map.of("type", "message", "data", message)));
        } catch (JsonProcessingException exception) {
            // 消息已入库，推送失败不应让发送者误以为保存失败。
            log.warn("消息序列化失败，客户端可通过历史接口补读");
            return;
        }
        for (WebSocketSession session : sockets.values()) {
            Member member = (Member) session.getAttributes().get(SessionHandshakeInterceptor.MEMBER_ATTRIBUTE);
            try {
                if (!session.isOpen() || !member.getExpiresAt().isAfter(ChatTime.now())) {
                    disconnect(session);
                    continue;
                }
                session.sendMessage(payload);
            } catch (IOException | RuntimeException exception) {
                disconnect(session);
            }
        }
    }

    /** 登录撤销事务提交后立即关闭匹配连接，循环内不访问数据库。 */
    @TransactionalEventListener(fallbackExecution = true)
    public synchronized void revoke(SessionRevoked event) {
        for (Map<String, WebSocketSession> sockets : rooms.values()) {
            for (WebSocketSession session : sockets.values()) {
                Member member = (Member) session.getAttributes().get(SessionHandshakeInterceptor.MEMBER_ATTRIBUTE);
                if ((event.userId() != null && event.userId().equals(member.getUserId()))
                        || (event.sessionId() != null && event.sessionId().equals(member.getLoginSessionId()))) {
                    disconnect(session);
                }
            }
        }
    }

    /** 定期关闭过期连接，不依赖房间后续有消息。 */
    @Scheduled(fixedDelay = 30000)
    public void expireConnections() {
        for (Map<String, WebSocketSession> sockets : rooms.values()) {
            for (WebSocketSession session : sockets.values()) {
                Member member = (Member) session.getAttributes().get(SessionHandshakeInterceptor.MEMBER_ATTRIBUTE);
                if (!member.getExpiresAt().isAfter(ChatTime.now())) disconnect(session);
            }
        }
    }

    /** 连接关闭后清理索引和空房间。 */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Member member = (Member) session.getAttributes().get(SessionHandshakeInterceptor.MEMBER_ATTRIBUTE);
        rooms.computeIfPresent(member.getRoomId(), (roomId, sockets) -> {
            sockets.remove(session.getId());
            return sockets.isEmpty() ? null : sockets;
        });
    }

    /** 传输异常时主动释放连接。 */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        disconnect(session);
    }

    /** 关闭异常或过期连接，失败时仍清理内存索引。 */
    private void disconnect(WebSocketSession session) {
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException | RuntimeException exception) {
            log.debug("WebSocket 连接已失效");
        } finally {
            afterConnectionClosed(session, CloseStatus.POLICY_VIOLATION);
        }
    }
}
