package com.mairy.chat.service;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.mairy.chat.dto.ChatViews.MemberView;
import com.mairy.chat.entity.Member;
import com.mairy.chat.mapper.MemberMapper;
import com.mairy.chat.util.ChatTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 匿名会话业务复用 MyBatis-Plus 3.5.18 的 Spring 服务实现。 */
@Service
@RequiredArgsConstructor
public class MemberService extends ServiceImpl<MemberMapper, Member> {
    private final RoomService roomService;

    /** 以上海时间记录进房并签发有效期 24 小时的匿名会话。 */
    @Transactional
    public MemberView joinRoom(Long roomId, String nickname) {
        roomService.requireRoom(roomId);
        Member member = new Member();
        member.setRoomId(roomId);
        member.setNickname(nickname.strip());
        member.setToken(UUID.randomUUID().toString());
        member.setJoinedAt(ChatTime.now());
        member.setExpiresAt(member.getJoinedAt().plusHours(24));
        save(member);
        return new MemberView(member.getId().toString(), roomId.toString(), member.getNickname(),
                member.getToken(), member.getExpiresAt());
    }

    /** 以上海时间统一校验 REST 与 WebSocket 的会话凭证和有效期。 */
    public Member requireSession(String token) {
        if (token == null || token.length() != 36) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先输入昵称进房");
        }
        Member member = lambdaQuery().eq(Member::getToken, token).one();
        if (member == null || !member.getExpiresAt().isAfter(ChatTime.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "会话已失效，请重新进房");
        }
        return member;
    }

    /** 确认会话属于目标房间，避免跨房间读写。 */
    public Member requireRoomSession(Long roomId, String token) {
        Member member = requireSession(token);
        if (!roomId.equals(member.getRoomId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前会话不属于该房间");
        }
        return member;
    }
}
