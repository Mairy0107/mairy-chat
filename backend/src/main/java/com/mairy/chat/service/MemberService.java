package com.mairy.chat.service;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.mairy.chat.dto.ChatViews.MemberView;
import com.mairy.chat.entity.Member;
import com.mairy.chat.security.AuthIdentity;
import com.mairy.chat.mapper.MemberMapper;
import com.mairy.chat.util.ChatTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 账号进房业务复用 MyBatis-Plus 3.5.18 的 Spring 服务实现。 */
@Service
@RequiredArgsConstructor
public class MemberService extends ServiceImpl<MemberMapper, Member> {
    private final RoomService roomService;
    private final AuthService auth;

    /** 以上海时间绑定登录账号进房；隐身会话仅限管理员且只读。 */
    @Transactional
    public MemberView joinRoom(Long roomId, boolean stealth) {
        roomService.requireRoom(roomId);
        AuthIdentity identity = auth.current();
        if (stealth && !"ADMIN".equals(identity.user().getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可以隐身进入房间");
        }
        Member member = new Member();
        member.setUserId(identity.user().getId());
        member.setLoginSessionId(identity.session().getId());
        member.setStealth(stealth);
        member.setRoomId(roomId);
        member.setNickname(identity.user().getNickname());
        member.setToken(UUID.randomUUID().toString());
        member.setJoinedAt(ChatTime.now());
        member.setExpiresAt(identity.session().getExpiresAt());
        save(member);
        return new MemberView(member.getId().toString(), roomId.toString(), member.getNickname(),
                member.getToken(), member.getExpiresAt());
    }

    /** 以上海时间统一校验 REST 与 WebSocket 的会话凭证和有效期。 */
    public Member requireSession(String token) {
        if (token == null || token.length() != 36) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录并进入房间");
        }
        Member member = lambdaQuery().eq(Member::getToken, token).one();
        if (member == null || !member.getExpiresAt().isAfter(ChatTime.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "会话已失效，请重新进房");
        }
        AuthIdentity identity = auth.requireActiveSession(member.getLoginSessionId());
        if (!identity.user().getId().equals(member.getUserId())
                || (Boolean.TRUE.equals(member.getStealth()) && !"ADMIN".equals(identity.user().getRole()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "进房身份无效");
        }
        return member;
    }

    /** 确认会话属于目标房间，避免跨房间读写。 */
    public Member requireRoomSession(Long roomId, String token) {
        Member member = requireSession(token);
        AuthIdentity identity = auth.current();
        if (!identity.user().getId().equals(member.getUserId())
                || !identity.session().getId().equals(member.getLoginSessionId())
                || !roomId.equals(member.getRoomId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前会话不属于该房间");
        }
        return member;
    }
}
