package com.mairy.chat.controller;

import com.mairy.chat.dto.AuthRequests;
import com.mairy.chat.dto.AuthViews;
import com.mairy.chat.service.AuthService;
import com.mairy.chat.service.IpSecurityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 注册、登录、退出及人工找回入口。 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService auth;
    private final IpSecurityService ips;

    /** 注册始终创建 USER，不接收角色参数。 */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthViews.UserView register(@Valid @RequestBody AuthRequests.Register request,
                                       HttpServletRequest servlet) {
        return auth.register(request, servlet.getHeader("User-Agent"));
    }

    /** 登录失败累计到IP封禁策略。 */
    @PostMapping("/login")
    public AuthViews.LoginView login(@Valid @RequestBody AuthRequests.Login request, HttpServletRequest servlet) {
        try {
            var result = auth.login(request.username(), request.password());
            ips.loginSucceeded(servlet.getRemoteAddr());
            return result;
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                ips.loginFailed(servlet.getRemoteAddr());
            }
            throw exception;
        }
    }

    /** 返回当前账号的公开资料，刷新时验证登录有效性。 */
    @GetMapping("/me")
    public AuthViews.UserView me() {
        return AuthViews.user(auth.current().user());
    }

    /** 退出撤销服务端登录凭证。 */
    @PostMapping("/logout")
    public Map<String, String> logout() {
        auth.logout();
        return Map.of("message", "已退出登录");
    }

    /** 统一回复，账号核实及临时密码交付由管理员线下处理。 */
    @PostMapping("/forgot-password")
    public Map<String, String> forgot(@Valid @RequestBody AuthRequests.ResetApplication request) {
        auth.requestReset(request.username(), request.note());
        return Map.of("message", "若账号存在，申请已提交。请联系管理员核实身份并领取临时密码。");
    }
}
