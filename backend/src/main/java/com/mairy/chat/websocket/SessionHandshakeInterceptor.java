package com.mairy.chat.websocket;

import com.mairy.chat.service.MemberService;
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

@Component
@RequiredArgsConstructor
public class SessionHandshakeInterceptor implements HandshakeInterceptor {
    public static final String MEMBER_ATTRIBUTE = "chatMember";
    private final MemberService memberService;
    private final IpSecurityService ipSecurity;

    /** 握手时验证绑定有效登录态的进房凭证，并由服务端确定连接所属房间。 */
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        String token = UriComponentsBuilder.fromUri(request.getURI()).build()
                .getQueryParams().getFirst("token");
        try {
            if (request.getRemoteAddress() == null) {
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return false;
            }
            ipSecurity.check(request.getRemoteAddress().getAddress().getHostAddress(), "/ws/chat");
            attributes.put(MEMBER_ATTRIBUTE, memberService.requireSession(token));
            return true;
        } catch (ResponseStatusException exception) {
            response.setStatusCode(exception.getStatusCode());
            return false;
        } catch (org.springframework.dao.DataAccessException exception) {
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }
    }

    /** 握手结束无需额外处理，不记录带有凭证的 URL。 */
    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler handler, Exception exception) {
    }
}
