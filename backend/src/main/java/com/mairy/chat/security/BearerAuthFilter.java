package com.mairy.chat.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mairy.chat.service.AuthService;
import com.mairy.chat.service.IpSecurityService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

/** API限流与随机 Bearer 登录态过滤器，不创建 Cookie 会话。 */
@RequiredArgsConstructor
public class BearerAuthFilter extends OncePerRequestFilter {
    private final AuthService authService;
    private final IpSecurityService ipSecurity;
    private final ObjectMapper objectMapper;

    /** 预检由 CORS 处理，业务请求先限流再校验登录。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 兼容容器与MockMvc不同的servletPath映射，统一取应用内请求路径。
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith("/api/") || "OPTIONS".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        try {
            ipSecurity.check(request.getRemoteAddr(), path);
            String header = request.getHeader("Authorization");
            if (header != null) {
                if (!header.startsWith("Bearer ")) {
                    throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "登录凭证格式错误");
                }
                AuthIdentity identity = authService.authenticate(header.substring(7));
                var authentication = new UsernamePasswordAuthenticationToken(identity, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + identity.user().getRole())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (ResponseStatusException exception) {
            writeError(response, exception.getStatusCode().value(), exception.getReason());
            return;
        } catch (DataAccessException exception) {
            writeError(response, 503, "数据库暂不可用，请检查本地配置和初始化脚本");
            return;
        }
        chain.doFilter(request, response);
    }

    /** 过滤器异常也遵守统一 JSON 错误格式。 */
    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), Map.of("message", message));
    }
}
