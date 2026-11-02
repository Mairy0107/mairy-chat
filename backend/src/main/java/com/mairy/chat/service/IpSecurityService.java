package com.mairy.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mairy.chat.entity.IpBlock;
import com.mairy.chat.mapper.IpBlockMapper;
import com.mairy.chat.util.ChatTime;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 单实例固定窗口限流，登录失败封禁落库，不信任伪造代理头。 */
@Service
@RequiredArgsConstructor
public class IpSecurityService {
    private final IpBlockMapper blocks;
    private final Map<String, Window> windows = new HashMap<>();
    private Instant lastCleanup = Instant.EPOCH;

    /** 检查持久封禁及接口频率，阈值超过后返回429。 */
    public synchronized void check(String ip, String path) {
        IpBlock block = blocks.selectById(ip);
        if (block != null && block.getUntilAt().isAfter(ChatTime.now())) {
            throw limited("该IP暂时封禁，请稍后重试");
        }
        consume("api:" + ip, 240, Duration.ofMinutes(1));
        if (path.equals("/api/auth/login")) {
            consume("login:" + ip, 20, Duration.ofMinutes(15));
        } else if (path.equals("/api/auth/register")) {
            consume("register:" + ip, 5, Duration.ofHours(1));
        } else if (path.equals("/api/auth/forgot-password")) {
            consume("reset:" + ip, 5, Duration.ofHours(1));
        }
    }

    /** 十五分钟内五次错误登录封禁十五分钟，重启后封禁仍生效。 */
    public synchronized void loginFailed(String ip) {
        Window window = window("fail:" + ip, Duration.ofMinutes(15));
        if (++window.count >= 5) {
            IpBlock block = new IpBlock();
            block.setIp(ip);
            block.setReason("十五分钟内登录失败至少5次");
            block.setUntilAt(ChatTime.now().plusMinutes(15));
            if (blocks.updateById(block) == 0) {
                blocks.insert(block);
            }
        }
    }

    /** 成功登录后清除该IP的失败计数，保留请求频率限制。 */
    public synchronized void loginSucceeded(String ip) {
        windows.remove("fail:" + ip);
    }

    /** 管理后台查看仍有效的封禁，限制响应数量。 */
    public List<IpBlock> listBlocks() {
        return blocks.selectList(new LambdaQueryWrapper<IpBlock>()
                .gt(IpBlock::getUntilAt, ChatTime.now()).orderByDesc(IpBlock::getUntilAt).last("LIMIT 100"));
    }

    /** 解封并清理当前实例的该IP计数。 */
    public synchronized void unblock(String ip) {
        blocks.deleteById(ip);
        windows.keySet().removeIf(key -> key.endsWith(":" + ip));
    }

    /** 在内存中消费固定窗口配额。 */
    private void consume(String key, int limit, Duration duration) {
        if (++window(key, duration).count > limit) {
            throw limited("请求过于频繁，请稍后重试");
        }
    }

    /** 清理过期窗口并限制内存容量，避免任意IP耗尽内存。 */
    private Window window(String key, Duration duration) {
        Instant now = Instant.now();
        if (lastCleanup.plusSeconds(60).isBefore(now) || windows.size() >= 10000) {
            windows.values().removeIf(value -> !value.until.isAfter(now));
            lastCleanup = now;
        }
        Window value = windows.get(key);
        if (value == null || !value.until.isAfter(now)) {
            if (value == null && windows.size() >= 10000) {
                throw limited("服务繁忙，请稍后重试");
            }
            value = new Window(now.plus(duration));
            windows.put(key, value);
        }
        return value;
    }

    /** 统一限流状态与中文错误。 */
    private ResponseStatusException limited(String message) {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message);
    }

    /** 保存单一固定窗口的到期时间与计数。 */
    private static final class Window {
        private final Instant until;
        private int count;

        /** 初始化空计数窗口。 */
        private Window(Instant until) {
            this.until = until;
        }
    }
}
