package com.mairy.chat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mairy.chat.dto.E2eeRequests.GrantKey;
import com.mairy.chat.dto.E2eeViews;
import com.mairy.chat.entity.Member;
import com.mairy.chat.entity.Room;
import com.mairy.chat.entity.RoomKeyWrap;
import com.mairy.chat.entity.UserPublicKey;
import com.mairy.chat.mapper.RoomMapper;
import com.mairy.chat.mapper.RoomKeyWrapMapper;
import com.mairy.chat.mapper.UserPublicKeyMapper;
import com.mairy.chat.util.ChatTime;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 服务端仅验证身份和密文形状，绝不生成、解封或托管内容密钥。 */
@Service
@RequiredArgsConstructor
public class E2eeService {
    private final AuthService auth;
    private final MemberService members;
    private final RoomMapper rooms;
    private final UserPublicKeyMapper publicKeys;
    private final RoomKeyWrapMapper wraps;

    /** 读取自己的公开身份；管理员没有加密成员身份。 */
    public E2eeViews.PublicKeyView mine() {
        requireUser();
        UserPublicKey key = publicKeys.selectById(auth.current().user().getId());
        return key == null ? null : new E2eeViews.PublicKeyView(key.getUserId().toString(), key.getPublicKey(), key.getFingerprint());
    }

    /** 首次登记公钥后禁止替换，密码重置不会赋予新的解密身份。 */
    @Transactional
    public E2eeViews.PublicKeyView publish(String encoded) {
        requireUser();
        byte[] bytes = decode(encoded, -1);
        try {
            RSAPublicKey rsa = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
            if (rsa.getModulus().bitLength() != 3072 || !rsa.getPublicExponent().equals(java.math.BigInteger.valueOf(65537))) {
                throw new IllegalArgumentException();
            }
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            UserPublicKey existing = publicKeys.selectById(auth.current().user().getId());
            if (existing != null) {
                if (!existing.getPublicKey().equals(encoded)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "已有设备身份，禁止覆盖；请使用原设备密钥");
                }
                return mine();
            }
            UserPublicKey key = new UserPublicKey();
            key.setUserId(auth.current().user().getId());
            key.setPublicKey(encoded);
            key.setFingerprint(fingerprint);
            key.setCreatedAt(ChatTime.now());
            publicKeys.insert(key);
            return new E2eeViews.PublicKeyView(key.getUserId().toString(), encoded, fingerprint);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "公钥格式无效");
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "设备身份已登记，请重新解锁");
        }
    }

    /** 按房间会话读取自己的信封；隐身管理员始终无法取得信封。 */
    public E2eeViews.KeyState state(Long roomId, String token) {
        Member member = requireMember(roomId, token);
        Room room = rooms.selectById(roomId);
        RoomKeyWrap own = findWrap(roomId, member.getUserId());
        boolean initialized = room.getOwnerUserId() != null && findWrap(roomId, room.getOwnerUserId()) != null;
        return new E2eeViews.KeyState(room.getOwnerUserId() == null ? null : room.getOwnerUserId().toString(),
                initialized, own == null ? null : own.getFingerprint(), own == null ? null : own.getWrappedKey());
    }

    /** 房主一次关联查询待授权成员；进入房间并不自动取得解密权限。 */
    public List<E2eeViews.Candidate> candidates(Long roomId, String token) {
        Member member = requireMember(roomId, token);
        requireOwner(rooms.selectById(roomId), member);
        return wraps.candidates(roomId, ChatTime.now());
    }

    /** 房主客户端提交成员专属信封；房间行锁防止并发初始化不同内容密钥。 */
    @Transactional
    public void grant(Long roomId, String token, Long userId, GrantKey request) {
        Member member = requireMember(roomId, token);
        Room room = rooms.selectOne(Wrappers.<Room>lambdaQuery().eq(Room::getId, roomId).last("FOR UPDATE"));
        requireOwner(room, member);
        decode(request.wrappedKey(), 384);
        UserPublicKey recipient = publicKeys.selectById(userId);
        var user = auth.getById(userId);
        if (recipient == null || user == null || !"USER".equals(user.getRole()) || Boolean.TRUE.equals(user.getDisabled())
                || !recipient.getFingerprint().equals(request.fingerprint())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "收件人公钥或普通成员身份无效");
        }
        if (!userId.equals(member.getUserId())) {
            if (findWrap(roomId, member.getUserId()) == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "房主必须先初始化本设备密钥");
            }
            boolean eligible = wraps.candidates(roomId, ChatTime.now()).stream().anyMatch(row -> row.userId().equals(userId.toString()));
            // 已有授权允许同值重试，其他用户必须有有效的普通进房会话。
            if (!eligible && findWrap(roomId, userId) == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请让成员先以普通身份进入房间");
            }
        }
        RoomKeyWrap existing = findWrap(roomId, userId);
        if (existing != null) {
            if (!existing.getWrappedKey().equals(request.wrappedKey()) || !existing.getFingerprint().equals(request.fingerprint())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "密钥信封已存在，禁止覆盖");
            }
            return;
        }
        RoomKeyWrap row = new RoomKeyWrap();
        row.setRoomId(roomId);
        row.setUserId(userId);
        row.setFingerprint(request.fingerprint());
        row.setWrappedKey(request.wrappedKey());
        row.setCreatedAt(ChatTime.now());
        wraps.insert(row);
    }

    /** 新消息只允许已获密钥的普通成员发送，管理员无法借普通进房绕过。 */
    public void requireSender(Long roomId, Member member) {
        requireUser();
        if (findWrap(roomId, member.getUserId()) == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "尚未获得房间密钥，请联系房主授权");
        }
    }

    /** 统一校验规范 Base64 编码及密文长度，防止歧义与错误元数据。 */
    public static byte[] decode(String encoded, int length) {
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (!Base64.getEncoder().encodeToString(bytes).equals(encoded) || (length >= 0 && bytes.length != length)) {
                throw new IllegalArgumentException();
            }
            return bytes;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "加密数据格式无效");
        }
    }

    /** 当前平台管理员始终没有普通加密身份，无主旧房间也不可冒认房主。 */
    private void requireUser() {
        if (!"USER".equals(auth.current().user().getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "管理员只能查看元数据和密文，不能取得密钥");
        }
    }

    /** 复用登录绑定的房间会话校验，并排除隐身会话。 */
    private Member requireMember(Long roomId, String token) {
        Member member = members.requireRoomSession(roomId, token);
        requireUser();
        if (Boolean.TRUE.equals(member.getStealth())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "隐身会话不能取得密钥");
        }
        return member;
    }

    /** 只有真实房主可初始化或批准分发，角色权限不能替代房主身份。 */
    private void requireOwner(Room room, Member member) {
        if (room == null || !member.getUserId().equals(room.getOwnerUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅房主可以授权密钥");
        }
    }

    /** 按唯一房间成员对读取不可变信封。 */
    private RoomKeyWrap findWrap(Long roomId, Long userId) {
        return wraps.selectOne(Wrappers.<RoomKeyWrap>lambdaQuery().eq(RoomKeyWrap::getRoomId, roomId)
                .eq(RoomKeyWrap::getUserId, userId));
    }
}
