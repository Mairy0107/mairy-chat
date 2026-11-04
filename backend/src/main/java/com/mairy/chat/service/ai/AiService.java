package com.mairy.chat.service.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.mairy.chat.config.AiProperties;
import com.mairy.chat.dto.AiViews;
import com.mairy.chat.entity.AiMessage;
import com.mairy.chat.entity.AiThread;
import com.mairy.chat.mapper.AiMessageMapper;
import com.mairy.chat.mapper.AiThreadMapper;
import com.mairy.chat.mapper.AiUsageMapper;
import com.mairy.chat.service.AuthService;
import com.mairy.chat.util.ChatTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 助手归属、配额和短事务；模型调用与流传输由独立服务处理。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiService extends ServiceImpl<AiThreadMapper, AiThread> {
    private static final String SYSTEM_PROMPT = "你是 Mairy Chat 的友善中文助手。简洁、准确地帮助用户；"
            + "不确定时说不知道，不编造用户的经历、身份或个人资料。"
            + "拒绝索取、生成或泄露密码、API密钥、私钥等秘密，提醒用户不要发送这些内容。"
            + "只提供文字帮助，没有访问房间、私信、用户档案或外部工具的能力。";
    private final AuthService auth;
    private final AiProperties properties;
    private final AiMessageMapper messages;
    private final AiUsageMapper usage;
    private final SensitiveWordFilter filter;

    /** 状态始终保留 P1 未完成标记，密钥缺失不影响入口可见。 */
    public AiViews.Status status() {
        Long userId = auth.current().user().getId();
        return new AiViews.Status(enabled(), remaining(userId), properties.getDailyQuota(), false);
    }

    /** 创建空会话不调用模型、不消耗额度。 */
    public AiViews.ThreadView open() {
        AiThread thread = new AiThread();
        thread.setUserId(auth.current().user().getId());
        thread.setCreatedAt(ChatTime.now());
        thread.setUpdatedAt(thread.getCreatedAt());
        save(thread);
        return threadView(thread);
    }

    /** 最近一百个本人会话按更新时间排序。 */
    public List<AiViews.ThreadView> threads() {
        return lambdaQuery().eq(AiThread::getUserId, auth.current().user().getId())
                .orderByDesc(AiThread::getUpdatedAt, AiThread::getId).last("LIMIT 100").list()
                .stream().map(this::threadView).toList();
    }

    /** 所有历史先校验归属，再按主键游标读取。 */
    public AiViews.MessagePage history(Long threadId, Long beforeId, int size) {
        requireMine(threadId, auth.current().user().getId(), false);
        List<AiMessage> rows = recent(threadId, beforeId, size + 1);
        boolean hasMore = rows.size() > size;
        if (hasMore) rows.remove(rows.size() - 1);
        Collections.reverse(rows);
        return new AiViews.MessagePage(rows.stream().map(this::messageView).toList(),
                rows.isEmpty() ? null : rows.get(0).getId().toString(), hasMore);
    }

    /** 入库用户消息、额度和生成租约共用事务；网络请求在事务提交后进行。 */
    @Transactional
    public Turn prepare(Long threadId, String content) {
        Long userId = auth.current().user().getId();
        AiThread thread = requireMine(threadId, userId, true);
        if (!enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "助手未配置");
        if (filter.blocked(content)) {
            log.info("ai_audit type=input_blocked");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, SensitiveWordFilter.REFUSAL);
        }
        var now = ChatTime.now();
        if (thread.getBusyUntil() != null && thread.getBusyUntil().isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该对话正在回复，请稍后再试");
        }
        usage.ensure(userId, now.toLocalDate());
        if (usage.consume(userId, now.toLocalDate(), properties.getDailyQuota()) != 1) {
            log.info("ai_audit type=quota_exceeded");
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "今日助手次数已用完，明天再来");
        }
        String requestId = UUID.randomUUID().toString();
        thread.setActiveRequestId(requestId);
        thread.setBusyUntil(now.plusMinutes(3));
        thread.setUpdatedAt(now);
        if (thread.getTitle() == null) thread.setTitle(content.substring(0, content.offsetByCodePoints(0,
                Math.min(20, content.codePointCount(0, content.length())))));
        updateById(thread);
        AiMessage user = insertMessage(threadId, "user", content);
        // 一次查询后在内存裁剪上下文，最多20条及24000字符，不循环查询SQL。
        List<AiMessage> rows = recent(threadId, null, 20);
        List<Map<String, String>> context = new ArrayList<>();
        int length = 0;
        for (AiMessage row : rows) {
            if (length + row.getContent().length() > 24000) break;
            context.add(Map.of("role", row.getRole(), "content", row.getContent()));
            length += row.getContent().length();
        }
        Collections.reverse(context);
        context.add(0, Map.of("role", "system", "content", SYSTEM_PROMPT));
        return new Turn(threadId, userId, requestId, messageView(user), List.copyOf(context));
    }

    /** 最终回复与释放租约原子保存，旧租约不得写入新一轮对话。 */
    @Transactional
    public AiViews.MessageView finish(Turn turn, String content) {
        if (baseMapper.release(turn.threadId(), turn.userId(), turn.requestId(), ChatTime.now()) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "回复已过期，请刷新对话");
        }
        return messageView(insertMessage(turn.threadId(), "assistant", content));
    }

    /** 保存失败时尽力释放本次租约，进程中止时由租约到期恢复。 */
    public void release(Turn turn) {
        baseMapper.release(turn.threadId(), turn.userId(), turn.requestId(), ChatTime.now());
    }

    /** 查询上海当天的剩余次数，午夜结束的请求展示新一天额度。 */
    public int remaining(Long userId) {
        Integer used = usage.count(userId, ChatTime.now().toLocalDate());
        return Math.max(0, properties.getDailyQuota() - (used == null ? 0 : used));
    }

    /** 空白密钥不尝试发送任何上游请求。 */
    private boolean enabled() {
        return properties.getApiKey() != null && !properties.getApiKey().isBlank();
    }

    /** 不向无关用户区分会话不存在或属于他人。 */
    private AiThread requireMine(Long threadId, Long userId, boolean lock) {
        AiThread thread = lock ? baseMapper.lockMine(threadId, userId) : lambdaQuery()
                .eq(AiThread::getId, threadId).eq(AiThread::getUserId, userId).one();
        if (thread == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该对话");
        return thread;
    }

    /** 一次读取倒序消息页，调用方决定是否反转。 */
    private List<AiMessage> recent(Long threadId, Long beforeId, int size) {
        return new ArrayList<>(messages.selectPage(new Page<>(1, size, false),
                new LambdaQueryWrapper<AiMessage>().eq(AiMessage::getThreadId, threadId)
                        .lt(beforeId != null, AiMessage::getId, beforeId).orderByDesc(AiMessage::getId)).getRecords());
    }

    /** 固定服务端角色与上海时间，不接受客户端身份字段。 */
    private AiMessage insertMessage(Long threadId, String role, String content) {
        AiMessage message = new AiMessage();
        message.setThreadId(threadId);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(ChatTime.now());
        messages.insert(message);
        return message;
    }

    /** 公开会话不返回账号与内部并发控制字段。 */
    private AiViews.ThreadView threadView(AiThread thread) {
        return new AiViews.ThreadView(thread.getId().toString(), thread.getTitle(), thread.getCreatedAt(), thread.getUpdatedAt());
    }

    /** 主键以字符串返回，避免浏览器精度丢失。 */
    private AiViews.MessageView messageView(AiMessage message) {
        return new AiViews.MessageView(message.getId().toString(), message.getThreadId().toString(),
                message.getRole(), message.getContent(), message.getCreatedAt());
    }

    /** 内部流上下文禁止自动生成包含正文的 toString。 */
    public record Turn(Long threadId, Long userId, String requestId, AiViews.MessageView user,
                       List<Map<String, String>> context) {
        /** 防止调试时泄露输入与上下文。 */
        @Override public String toString() { return "AiTurn"; }
    }
}
