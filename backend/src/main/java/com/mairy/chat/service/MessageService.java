package com.mairy.chat.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.mairy.chat.dto.ChatViews.MessagePage;
import com.mairy.chat.dto.ChatViews.MessageView;
import com.mairy.chat.entity.Member;
import com.mairy.chat.entity.Message;
import com.mairy.chat.mapper.MessageMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 消息业务复用 MyBatis-Plus 3.5.18 的 Spring 服务实现。 */
@Service
@RequiredArgsConstructor
public class MessageService extends ServiceImpl<MessageMapper, Message> {
    private final MemberService memberService;

    /** 按主键游标读取历史；实时插入不会挤动已有分页。 */
    public MessagePage history(Long roomId, String token, Long beforeId, int size) {
        memberService.requireRoomSession(roomId, token);
        List<Message> rows = new ArrayList<>(lambdaQuery()
                .eq(Message::getRoomId, roomId)
                .lt(beforeId != null, Message::getId, beforeId)
                .orderByDesc(Message::getId)
                .page(new Page<>(1, size + 1, false)).getRecords());
        boolean hasMore = rows.size() > size;
        if (hasMore) {
            rows.remove(rows.size() - 1);
        }
        Collections.reverse(rows);
        String nextBeforeId = rows.isEmpty() ? null : rows.get(0).getId().toString();
        return new MessagePage(rows.stream().map(this::toView).toList(), nextBeforeId, hasMore);
    }

    /** 以服务端会话身份保存消息，事务提交后由控制器触发推送。 */
    @Transactional
    public MessageView send(Long roomId, String token, String content) {
        Member member = memberService.requireRoomSession(roomId, token);
        Message message = new Message();
        message.setRoomId(roomId);
        message.setMemberId(member.getId());
        message.setNickname(member.getNickname());
        message.setContent(content.strip());
        message.setCreatedAt(LocalDateTime.now());
        save(message);
        return toView(message);
    }

    /** 转换消息快照，不逐条查询成员或房间。 */
    private MessageView toView(Message message) {
        return new MessageView(message.getId().toString(), message.getRoomId().toString(),
                message.getMemberId().toString(), message.getNickname(), message.getContent(),
                message.getCreatedAt());
    }
}
