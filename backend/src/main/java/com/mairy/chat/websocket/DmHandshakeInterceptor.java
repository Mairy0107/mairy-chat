package com.mairy.chat.websocket;

import com.mairy.chat.service.AuthService;
import com.mairy.chat.service.IpSecurityService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/** 私信握手使用登录 Bearer，绝不接受房间成员凭证。 */
@Component
@RequiredArgsConstructor
public class DmHandshakeInterceptor implements HandshakeInterceptor {
    public static final String IDENTITY_ATTRIBUTE = "dmIdentity";
    private final AuthService auth;
    private final IpSecurityService ipSecurity;

    /** 浏览器使用查询凭证，其他客户端也可携带 Authorization；不记录 URL。 */
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        try {
            if (request.getRemoteAddress() == null) {
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return false;
            }
            ipSecurity.check(request.getRemoteAddress().getAddress().getHostAddress(), "/ws/dm");
            String header = request.getHeaders().getFirst("Authorization");
            String token = header == null ? UriComponentsBuilder.fromUri(request.getURI()).build()
                    .getQueryParams().getFirst("token") : header.startsWith("Bearer ") ? header.substring(7) : null;
            attributes.put(IDENTITY_ATTRIBUTE, auth.authenticate(token));
            return true;
        } catch (ResponseStatusException exception) {
            response.setStatusCode(exception.getStatusCode());
            return false;
        } catch (org.springframework.dao.DataAccessException exception) {
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }
    }

    /** 握手完成不记录任何凭证或额外状态。 */
    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler handler, Exception exception) { }
}
