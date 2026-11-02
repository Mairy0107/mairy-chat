package com.mairy.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mairy.chat.dto.AuthViews;
import com.mairy.chat.dto.ChatViews.PageView;
import com.mairy.chat.entity.DeviceRegistration;
import com.mairy.chat.entity.LoginSession;
import com.mairy.chat.entity.PasswordResetRequest;
import com.mairy.chat.entity.User;
import com.mairy.chat.mapper.DeviceRegistrationMapper;
import com.mairy.chat.mapper.LoginSessionMapper;
import com.mairy.chat.mapper.PasswordResetRequestMapper;
import com.mairy.chat.mapper.UserMapper;
import com.mairy.chat.security.SessionRevoked;
import com.mairy.chat.util.ChatTime;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 后台按批量查询展示资料，重置密码时同步撤销全部旧登录。 */
@Service
@RequiredArgsConstructor
public class AdminService {
    private final AuthService auth;
    private final UserMapper users;
    private final PasswordResetRequestMapper resets;
    private final LoginSessionMapper sessions;
    private final DeviceRegistrationMapper devices;
    private final PasswordEncoder encoder;
    private final ApplicationEventPublisher events;

    /** 分页返回公开用户资料，不序列化密码散列。 */
    public PageView<AuthViews.UserView> users(long page) {
        Page<User> result = auth.lambdaQuery().orderByAsc(User::getPublicNo).page(new Page<>(page, 30));
        return new PageView<>(result.getRecords().stream().map(AuthViews::user).toList(), result.getTotal(), page, 30);
    }

    /** 查询最近200条申请并一次批量补全账号资料。 */
    public List<AuthViews.ResetView> resets() {
        var rows = resets.selectList(new LambdaQueryWrapper<PasswordResetRequest>()
                .orderByDesc(PasswordResetRequest::getId).last("LIMIT 200"));
        if (rows.isEmpty()) {
            return List.of();
        }
        var profiles = auth.listByIds(rows.stream().map(PasswordResetRequest::getUserId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return rows.stream().map(row -> new AuthViews.ResetView(row.getId().toString(),
                AuthViews.user(profiles.get(row.getUserId())), row.getStatus(), row.getNote(),
                row.getCreatedAt(), row.getHandledAt())).toList();
    }

    /** 按用户展示粗略设备信息，原始UA不对外返回。 */
    public List<DeviceView> devices(Long userId) {
        return devices.selectList(new LambdaQueryWrapper<DeviceRegistration>()
                .eq(DeviceRegistration::getUserId, userId)).stream()
                .map(row -> new DeviceView(row.getDeviceLabel(), row.getCreatedAt())).toList();
    }

    /** 锁账号后修改密码、处理所有待办并撤销旧会话。 */
    @Transactional
    public void resetPassword(Long userId, String password) {
        auth.validatePassword(password);
        User user = users.selectByIdForUpdate(userId);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        }
        user.setPasswordHash(encoder.encode(password));
        users.updateById(user);
        resets.update(null, new LambdaUpdateWrapper<PasswordResetRequest>()
                .eq(PasswordResetRequest::getUserId, userId).eq(PasswordResetRequest::getStatus, "PENDING")
                .set(PasswordResetRequest::getStatus, "HANDLED")
                .set(PasswordResetRequest::getHandledBy, auth.current().user().getId())
                .set(PasswordResetRequest::getHandledAt, ChatTime.now()));
        sessions.delete(new LambdaQueryWrapper<LoginSession>().eq(LoginSession::getUserId, userId));
        events.publishEvent(new SessionRevoked(userId, null));
    }

    public record DeviceView(String label, java.time.LocalDateTime registeredAt) { }
}
