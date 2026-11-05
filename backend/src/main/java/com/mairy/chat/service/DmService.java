package com.mairy.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.mairy.chat.dto.AuthViews;
import com.mairy.chat.dto.DmRequests;
import com.mairy.chat.dto.DmViews;
import com.mairy.chat.entity.DmMessage;
import com.mairy.chat.entity.DmThread;
import com.mairy.chat.entity.User;
import com.mairy.chat.mapper.DmMessageMapper;
import com.mairy.chat.mapper.DmThreadMapper;
import com.mairy.chat.util.ChatTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 私信独立于房间，每次读写都检查成员、好友关系及对方状态。 */
@Service
@RequiredArgsConstructor
public class DmService extends ServiceImpl<DmThreadMapper, DmThread> {
    private final AuthService auth;
    private final FriendService friends;
    private final DmMessageMapper messages;
    private final ApplicationEventPublisher events;

    /** 好友双向打开均返回同一会话，数据库约束处理并发。 */
    @Transactional
    public DmViews.ThreadView open(Long userId) {
        Long me = auth.current().user().getId();
        User peer = requirePeer(me, userId);
        long low = Math.min(me, userId);
        long high = Math.max(me, userId);
        baseMapper.ensureThread(low, high, ChatTime.now());
        DmThread thread = lambdaQuery().eq(DmThread::getUserLow, low).eq(DmThread::getUserHigh, high).one();
        return new DmViews.ThreadView(thread.getId().toString(), AuthViews.user(peer), thread.getCreatedAt());
    }

    /** 批量读取对方资料，不在会话转换循环中调用 SQL。 */
    public List<DmViews.ThreadView> threads() {
        Long me = auth.current().user().getId();
        List<DmThread> rows = baseMapper.listMine(me);
        if (rows.isEmpty()) return List.of();
        var ids = rows.stream().map(row -> peerId(row, me)).collect(Collectors.toSet());
        var peers = auth.listByIds(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return rows.stream().map(row -> new DmViews.ThreadView(row.getId().toString(),
                AuthViews.user(peers.get(peerId(row, me))), row.getCreatedAt())).toList();
    }

    /** 历史按主键倒序取一页后转正序，实时消息不会挤动游标。 */
    public DmViews.MessagePage history(Long threadId, Long beforeId, int size) {
        requireThread(threadId);
        List<DmMessage> rows = new ArrayList<>(messages.selectPage(new Page<>(1, size + 1, false),
                new LambdaQueryWrapper<DmMessage>().eq(DmMessage::getThreadId, threadId)
                        .lt(beforeId != null, DmMessage::getId, beforeId)
                        .orderByDesc(DmMessage::getId)).getRecords());
        boolean hasMore = rows.size() > size;
        if (hasMore) rows.remove(rows.size() - 1);
        Collections.reverse(rows);
        return new DmViews.MessagePage(rows.stream().map(this::toView).toList(),
                rows.isEmpty() ? null : rows.get(0).getId().toString(), hasMore);
    }

    /** 校验文字或明文语音，仅由登录身份确定发送人，固定 enc=0。 */
    @Transactional
    public DmViews.MessageView send(Long threadId, DmRequests.SendMessage request) {
        DmThread thread = requireThread(threadId);
        String kind = request.kind() == null ? "text" : request.kind();
        validateMessage(request, kind);
        DmMessage message = new DmMessage();
        message.setThreadId(threadId);
        message.setSenderId(auth.current().user().getId());
        message.setContent("voice".equals(kind) ? request.content() : request.content().strip());
        message.setKind(kind);
        message.setMime(request.mime());
        message.setDuration(request.duration() == null ? null : request.duration().intValueExact());
        message.setEnc(0);
        message.setCreatedAt(ChatTime.now());
        messages.insert(message);
        DmViews.MessageView view = toView(message);
        events.publishEvent(new DmMessageSent(thread.getUserLow(), thread.getUserHigh(), view));
        return view;
    }

    /** 类型白名单、规范 Base64 和音频限额均在入库前检查，绝不复用房间密钥。 */
    private void validateMessage(DmRequests.SendMessage request, String kind) {
        if ("text".equals(kind)) {
            if (request.content().length() > 1000 || request.mime() != null || request.duration() != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文字最多1000字，不能包含语音参数");
            }
            return;
        }
        if (!"voice".equals(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息类型无效");
        }
        if (request.mime() == null || !List.of("audio/webm;codecs=opus", "audio/webm",
                "audio/ogg;codecs=opus", "audio/ogg", "audio/mp4").contains(request.mime())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "语音格式无效");
        }
        try {
            int duration = request.duration() == null ? 0 : request.duration().intValueExact();
            if (duration < 1 || duration > 60000) throw new ArithmeticException();
        } catch (ArithmeticException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "语音时长须为1至60000整数毫秒");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(request.content());
            if (bytes.length < 1 || bytes.length > 512 * 1024
                    || !Base64.getEncoder().encodeToString(bytes).equals(request.content())) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "语音须为不超过512 KiB的规范Base64");
        }
    }

    /** REST 共用成员检查，不向无关用户暴露会话是否存在。 */
    private DmThread requireThread(Long threadId) {
        Long me = auth.current().user().getId();
        DmThread thread = getById(threadId);
        if (thread == null || (!me.equals(thread.getUserLow()) && !me.equals(thread.getUserHigh()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该私信");
        }
        requirePeer(me, peerId(thread, me));
        return thread;
    }

    /** 校验已接受好友及可用账号，所有非好友统一返回403。 */
    private User requirePeer(Long me, Long peerId) {
        friends.requireAccepted(me, peerId);
        User peer = auth.getById(peerId);
        if (peer == null || Boolean.TRUE.equals(peer.getDisabled())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "对方账号不可用");
        }
        return peer;
    }

    /** 从规范化用户对中取得对方主键。 */
    private Long peerId(DmThread thread, Long me) {
        return me.equals(thread.getUserLow()) ? thread.getUserHigh() : thread.getUserLow();
    }

    /** 返回固定明文标记，不暴露实体或额外身份字段。 */
    private DmViews.MessageView toView(DmMessage message) {
        return new DmViews.MessageView(message.getId().toString(), message.getThreadId().toString(),
                message.getSenderId().toString(), message.getContent(), 0, null, message.getCreatedAt(),
                message.getKind() == null ? "text" : message.getKind(), message.getMime(), message.getDuration());
    }
}
