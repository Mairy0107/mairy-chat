package com.mairy.chat.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.mairy.chat.dto.ChatRequests.SendMessage;
import com.mairy.chat.dto.ChatViews.MessagePage;
import com.mairy.chat.dto.ChatViews.MessageView;
import com.mairy.chat.entity.Member;
import com.mairy.chat.entity.Message;
import com.mairy.chat.mapper.MessageMapper;
import com.mairy.chat.util.ChatTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

/** 消息业务复用 MyBatis-Plus 3.5.18 的 Spring 服务实现。 */
@Service
@RequiredArgsConstructor
public class MessageService extends ServiceImpl<MessageMapper, Message> {
    private final MemberService memberService;
    private final E2eeService e2ee;

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

    /** 按文字或语音校验密文长度与成员权限，保存后由控制器推送。 */
    @Transactional
    public MessageView send(Long roomId, String token, SendMessage request) {
        Member member = memberService.requireRoomSession(roomId, token);
        if (Boolean.TRUE.equals(member.getStealth())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "隐身模式仅可阅读，请以普通模式进入后发送");
        }
        // 禁止明文降级，兼容仅限历史读取；服务端不接触解密密钥。
        if (!Integer.valueOf(1).equals(request.enc())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "新消息必须使用端到端加密");
        }
        e2ee.requireSender(roomId, member);
        E2eeService.decode(request.iv(), 12);
        String kind = request.kind() == null ? "text" : request.kind();
        if (!"text".equals(kind) && !"voice".equals(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息类型无效");
        }
        // 文字沿用原限制；语音允许700000字节载荷和16字节GCM标签。
        int maxLength = "voice".equals(kind) ? 700016 : 4016;
        int length = E2eeService.decode(request.content(), -1).length;
        if (length < 17 || length > maxLength) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "加密消息长度无效");
        }
        Message message = new Message();
        message.setRoomId(roomId);
        message.setMemberId(member.getId());
        message.setNickname(member.getNickname());
        message.setContent(request.content());
        message.setKind(kind);
        message.setEnc(1);
        message.setIv(request.iv());
        message.setCreatedAt(ChatTime.now());
        try {
            save(message);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "消息 IV 已使用，请重新发送");
        }
        return toView(message);
    }

    /** 转换消息快照，不逐条查询成员或房间。 */
    private MessageView toView(Message message) {
        return new MessageView(message.getId().toString(), message.getRoomId().toString(),
                message.getMemberId().toString(), message.getNickname(), message.getContent(),
                message.getEnc() == null ? 0 : message.getEnc(), message.getIv(), message.getCreatedAt(),
                message.getKind() == null ? "text" : message.getKind());
    }
}
