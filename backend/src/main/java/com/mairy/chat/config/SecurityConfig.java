package com.mairy.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mairy.chat.security.BearerAuthFilter;
import com.mairy.chat.service.AuthService;
import com.mairy.chat.service.IpSecurityService;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** 按路径和角色鉴权，沿用 MVC 配置的明确 CORS 来源。 */
@Configuration
public class SecurityConfig {
    /** 密码仅以 BCrypt 工作因子10保存。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    /** Bearer 不由浏览器自动附带，禁用 Cookie 会话及其 CSRF 机制。 */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, AuthService auth,
                                                  IpSecurityService ips, ObjectMapper mapper) throws Exception {
        return http.cors(Customizer.withDefaults()).csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(rules -> rules
                        .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST,
                                "/api/auth/login", "/api/auth/register", "/api/auth/forgot-password").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        // 私信握手由独立拦截器验证登录凭证。
                        .requestMatchers("/ws/chat", "/ws/dm").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setContentType("application/json;charset=UTF-8");
                            mapper.writeValue(response.getWriter(), Map.of("message", "请先登录"));
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(403);
                            response.setContentType("application/json;charset=UTF-8");
                            mapper.writeValue(response.getWriter(), Map.of("message", "需要管理员权限"));
                        }))
                .addFilterBefore(new BearerAuthFilter(auth, ips, mapper), UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
