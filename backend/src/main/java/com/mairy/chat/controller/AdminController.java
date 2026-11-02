package com.mairy.chat.controller;

import com.mairy.chat.dto.AuthRequests.ResetPassword;
import com.mairy.chat.dto.AuthViews;
import com.mairy.chat.dto.ChatViews.PageView;
import com.mairy.chat.entity.IpBlock;
import com.mairy.chat.service.AdminService;
import com.mairy.chat.service.IpSecurityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 所有后台入口由 SecurityConfig 统一要求 ADMIN 角色。 */
@Validated
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {
    private final AdminService admin;
    private final IpSecurityService ips;

    /** 用户分页列表。 */
    @GetMapping("/users")
    public PageView<AuthViews.UserView> users(@RequestParam(defaultValue = "1") @Positive long page) {
        return admin.users(page);
    }

    /** 展示用户注册设备的粗略类型。 */
    @GetMapping("/users/{id}/devices")
    public List<AdminService.DeviceView> devices(@PathVariable @Positive Long id) {
        return admin.devices(id);
    }

    /** 指定临时密码，同时处理该用户待办申请。 */
    @PostMapping("/users/{id}/reset-password")
    public Map<String, String> reset(@PathVariable @Positive Long id, @Valid @RequestBody ResetPassword request) {
        admin.resetPassword(id, request.password());
        return Map.of("message", "密码已重置，旧登录已撤销，请线下安全交付临时密码");
    }

    /** 查看人工密码找回申请。 */
    @GetMapping("/reset-requests")
    public List<AuthViews.ResetView> resets() {
        return admin.resets();
    }

    /** 查看有效IP封禁。 */
    @GetMapping("/ip-blocks")
    public List<IpBlock> blocks() {
        return ips.listBlocks();
    }

    /** 解封指定IP，不信任客户端代理头。 */
    @PostMapping("/ip-blocks/unblock")
    public Map<String, String> unblock(@RequestParam @Size(max = 45) String ip) {
        ips.unblock(ip);
        return Map.of("message", "已解封");
    }
}
