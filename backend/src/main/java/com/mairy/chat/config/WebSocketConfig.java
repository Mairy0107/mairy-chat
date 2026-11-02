package com.mairy.chat.config;

import com.mairy.chat.websocket.ChatSocketHandler;
import com.mairy.chat.websocket.SessionHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {
    private final ChatSocketHandler socketHandler;
    private final SessionHandshakeInterceptor handshakeInterceptor;
    @Value("${chat.allowed-origins}")
    private String[] allowedOrigins;

    /** 注册带匿名会话校验和来源限制的实时推送端点。 */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(socketHandler, "/ws/chat")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins(allowedOrigins);
    }
}
