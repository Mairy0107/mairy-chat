package com.mairy.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 账号请求仅接收允许修改的字段，角色始终由服务端决定。 */
public final class AuthRequests {
    /** 禁止实例化请求容器。 */
    private AuthRequests() { }

    /** 登录凭证。 */
    public record Login(
            @NotBlank(message = "必填内容不能为空") @Size(max = 32, message = "用户名最多32字") String username,
            @NotBlank(message = "必填内容不能为空") @Size(max = 64, message = "密码最多64字") String password) { }

    /** 注册资料，设备指纹为客户端 SHA-256 结果。 */
    public record Register(
            @Pattern(regexp = "[a-zA-Z0-9_]{3,32}", message = "用户名需为3至32位字母、数字或下划线") @NotBlank(message = "必填内容不能为空") String username,
            @NotBlank(message = "必填内容不能为空") @Size(min = 8, max = 64, message = "密码需为8至64字且不超过72字节") String password,
            @NotBlank(message = "必填内容不能为空") @Size(max = 20, message = "昵称最多20字") String nickname,
            @NotBlank(message = "必填内容不能为空") @Pattern(regexp = "[a-f0-9]{64}", message = "设备指纹格式错误") String machineFingerprint) { }

    /** 人工找回申请，不公开账号是否存在。 */
    public record ResetApplication(@NotBlank(message = "必填内容不能为空") @Size(max = 32, message = "用户名最多32字") String username,
                                   @Size(max = 200, message = "备注最多200字") String note) { }

    /** 管理员指定临时密码。 */
    public record ResetPassword(@NotBlank(message = "必填内容不能为空") @Size(min = 8, max = 64, message = "密码需为8至64字") String password) { }
}
