package com.mairy.chat.service;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.mairy.chat.dto.AuthViews;
import com.mairy.chat.entity.FriendRequest;
import com.mairy.chat.entity.User;
import com.mairy.chat.mapper.FriendRequestMapper;
import com.mairy.chat.util.ChatTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 好友请求表兼作已接受关系，双向用户对具有唯一约束。 */
@Service
@RequiredArgsConstructor
public class FriendService extends ServiceImpl<FriendRequestMapper, FriendRequest> {
    private final AuthService auth;

    /** 按编号或用户名、昵称搜索，最多返回30条公开资料。 */
    public List<AuthViews.UserView> search(String keyword) {
        String query = keyword.strip();
        var users = auth.lambdaQuery().ne(User::getId, auth.current().user().getId())
                .eq(User::getDisabled, false);
        if (query.matches("#?[0-9]{1,9}")) {
            users.eq(User::getPublicNo, Integer.parseInt(query.replace("#", "")));
        } else {
            // 转义LIKE通配符，避免输入百分号列出全部用户。
            String text = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            users.and(part -> part.like(User::getUsername, text).or().like(User::getNickname, text));
        }
        return users.orderByAsc(User::getPublicNo).last("LIMIT 30").list().stream().map(AuthViews::user).toList();
    }

    /** 发起好友申请，禁止自加、重复和向停用账号发起请求。 */
    @Transactional
    public void request(Long targetId) {
        Long me = auth.current().user().getId();
        User target = auth.getById(targetId);
        if (me.equals(targetId) || target == null || Boolean.TRUE.equals(target.getDisabled())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法向该用户发起好友申请");
        }
        FriendRequest request = new FriendRequest();
        request.setSenderId(me);
        request.setReceiverId(targetId);
        request.setStatus("PENDING");
        request.setCreatedAt(ChatTime.now());
        try {
            save(request);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已存在好友关系或申请，请查看请求列表");
        }
    }

    /** 一次批量读取涉及用户，结果转换时不逐条查询数据库。 */
    public List<AuthViews.RequestView> listRelations() {
        Long me = auth.current().user().getId();
        List<FriendRequest> rows = lambdaQuery().and(q -> q.eq(FriendRequest::getSenderId, me)
                .or().eq(FriendRequest::getReceiverId, me)).orderByDesc(FriendRequest::getId).last("LIMIT 200").list();
        if (rows.isEmpty()) {
            return List.of();
        }
        var ids = rows.stream().flatMap(row -> java.util.stream.Stream.of(row.getSenderId(), row.getReceiverId()))
                .collect(Collectors.toSet());
        Map<Long, User> users = auth.listByIds(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return rows.stream().map(row -> new AuthViews.RequestView(row.getId().toString(),
                AuthViews.user(users.get(row.getSenderId())), AuthViews.user(users.get(row.getReceiverId())),
                row.getStatus(), row.getCreatedAt())).toList();
    }

    /** 仅接收方可原子地同意或拒绝待处理申请。 */
    @Transactional
    public void respond(Long id, boolean accept) {
        boolean changed = lambdaUpdate().eq(FriendRequest::getId, id)
                .eq(FriendRequest::getReceiverId, auth.current().user().getId())
                .eq(FriendRequest::getStatus, "PENDING")
                .set(FriendRequest::getStatus, accept ? "ACCEPTED" : "REJECTED").update();
        if (!changed) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请求已处理或不属于你");
        }
    }
}
