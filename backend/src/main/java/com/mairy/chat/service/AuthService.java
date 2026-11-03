package com.mairy.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.mairy.chat.dto.AuthRequests.Register;
import com.mairy.chat.dto.AuthViews;
import com.mairy.chat.dto.AuthViews.LoginView;
import com.mairy.chat.entity.DeviceRegistration;
import com.mairy.chat.entity.LoginSession;
import com.mairy.chat.entity.PasswordResetRequest;
import com.mairy.chat.entity.User;
import com.mairy.chat.mapper.AuthQuotaMapper;
import com.mairy.chat.mapper.DeviceRegistrationMapper;
import com.mairy.chat.mapper.LoginSessionMapper;
import com.mairy.chat.mapper.PasswordResetRequestMapper;
import com.mairy.chat.mapper.UserMapper;
import com.mairy.chat.security.AuthIdentity;
import com.mairy.chat.security.SessionRevoked;
import com.mairy.chat.util.AuthTokens;
import com.mairy.chat.util.ChatTime;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 注册、可撤销登录态与人工找回，对外仅返回筛选后的字段。 */
@Service
@RequiredArgsConstructor
public class AuthService extends ServiceImpl<UserMapper, User> {
    private final PasswordEncoder passwordEncoder;
    private final AuthQuotaMapper quotas;
    private final DeviceRegistrationMapper devices;
    private final LoginSessionMapper sessions;
    private final PasswordResetRequestMapper resets;
    private final ApplicationEventPublisher events;
    // 避免不存在的用户名直接跳过 BCrypt。
    private final String dummyHash = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    /** 事务内锁定设备配额并分配公开编号，第四个账号会被拒绝。 */
    @Transactional
    public AuthViews.UserView register(Register request, String userAgent) {
        validatePassword(request.password());
        String fingerprint = AuthTokens.hash(request.machineFingerprint());
        quotas.ensureDevice(fingerprint);
        if (quotas.lockDevice(fingerprint) >= 3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该浏览器设备最多注册3个账号");
        }
        int publicNo = quotas.lockNumber() + 1;
        User user = new User();
        user.setPublicNo(publicNo);
        user.setUsername(request.username());
        user.setNickname(request.nickname().strip());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole("USER");
        user.setDisabled(false);
        user.setCreatedAt(ChatTime.now());
        try {
            save(user);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
        }
        quotas.incrementNumber();
        quotas.incrementDevice(fingerprint);
        String agent = userAgent == null ? "" : userAgent.substring(0, Math.min(512, userAgent.length()));
        DeviceRegistration device = new DeviceRegistration();
        device.setFingerprintHash(fingerprint);
        device.setUserId(user.getId());
        device.setUserAgent(agent);
        device.setDeviceLabel(deviceLabel(agent));
        device.setCreatedAt(ChatTime.now());
        devices.insert(device);
        return AuthViews.user(user);
    }

    /** 校验 BCrypt 密码并签发八小时随机 Bearer 凭证。 */
    @Transactional
    public LoginView login(String username, String password) {
        // 与重置共用行锁，避免旧密码在重置提交后签发新会话。
        User user = baseMapper.selectByUsernameForUpdate(username);
        boolean matches = passwordEncoder.matches(password, user == null ? dummyHash : user.getPasswordHash());
        if (!matches || user == null || Boolean.TRUE.equals(user.getDisabled())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误，或账号已停用");
        }
        String token = AuthTokens.generate();
        LoginSession session = new LoginSession();
        session.setId(AuthTokens.hash(token));
        session.setUserId(user.getId());
        session.setExpiresAt(ChatTime.now().plusHours(8));
        sessions.insert(session);
        return new LoginView(token, session.getExpiresAt(), AuthViews.user(user));
    }

    /** 只接受完整随机凭证，数据库查询使用散列。 */
    public AuthIdentity authenticate(String token) {
        if (token == null || !token.matches("[a-f0-9]{64}")) {
            throw unauthorized();
        }
        return requireActiveSession(AuthTokens.hash(token));
    }

    /** REST 和握手共用校验，拒绝撤销、过期和停用账号。 */
    public AuthIdentity requireActiveSession(String id) {
        LoginSession session = id == null ? null : sessions.selectById(id);
        if (session == null || !session.getExpiresAt().isAfter(ChatTime.now())) {
            throw unauthorized();
        }
        User user = getById(session.getUserId());
        if (user == null || Boolean.TRUE.equals(user.getDisabled())) {
            throw unauthorized();
        }
        return new AuthIdentity(user, session);
    }

    /** 推送前一次批量校验登录有效期、撤销及停用状态，禁止循环查询。 */
    public Set<String> activeSessionIds(Collection<String> ids) {
        if (ids.isEmpty()) return Set.of();
        return sessions.selectList(new LambdaQueryWrapper<LoginSession>()
                        .in(LoginSession::getId, ids).gt(LoginSession::getExpiresAt, ChatTime.now())
                        .exists("SELECT 1 FROM chat_user u WHERE u.id=chat_login_session.user_id AND u.disabled=FALSE"))
                .stream().map(LoginSession::getId).collect(Collectors.toSet());
    }

    /** 获取过滤器已验证的请求身份，不接受客户端指定身份。 */
    public AuthIdentity current() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthIdentity identity)) {
            throw unauthorized();
        }
        return identity;
    }

    /** 撤销当前登录，并在提交后关闭所属实时连接。 */
    @Transactional
    public void logout() {
        String id = current().session().getId();
        sessions.deleteById(id);
        events.publishEvent(new SessionRevoked(null, id));
    }

    /** 存在账号且无待办时记录申请；对外不透露账号存在性。 */
    @Transactional
    public void requestReset(String username, String note) {
        // 首次读取即锁定账号，避免MySQL可重复读快照导致重复待办。
        User user = baseMapper.selectByUsernameForUpdate(username);
        if (user == null) {
            return;
        }
        if (resets.selectCount(new LambdaQueryWrapper<PasswordResetRequest>()
                .eq(PasswordResetRequest::getUserId, user.getId())
                .eq(PasswordResetRequest::getStatus, "PENDING")) > 0) {
            return;
        }
        PasswordResetRequest reset = new PasswordResetRequest();
        reset.setUserId(user.getId());
        reset.setStatus("PENDING");
        reset.setNote(note == null ? "" : note.strip());
        reset.setCreatedAt(ChatTime.now());
        resets.insert(reset);
    }

    /** BCrypt 仅处理前72字节，额外验证 UTF-8 字节上限。 */
    public void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 64
                || password.isBlank() || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码需为8至64字且不超过72字节");
        }
    }

    /** 粗略识别客户端声明的系统与浏览器，不推断真实硬件。 */
    private String deviceLabel(String agent) {
        String os = agent.contains("iPhone") ? "iPhone" : agent.contains("Android") ? "Android"
                : agent.contains("Windows") ? "Windows" : agent.contains("Macintosh") ? "macOS" : "未知系统";
        String browser = agent.contains("Edg/") ? "Edge" : agent.contains("Firefox/") ? "Firefox"
                : agent.contains("Chrome/") ? "Chrome" : agent.contains("Safari/") ? "Safari" : "未知浏览器";
        return os + " / " + browser;
    }

    /** 统一登录失效提示。 */
    private ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }
}
