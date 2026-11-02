package com.mairy.chat.controller;

import com.mairy.chat.dto.AuthViews;
import com.mairy.chat.service.FriendService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 登录用户之间的搜索和双向好友申请。 */
@Validated
@RestController
@RequestMapping("/api/friends")
@RequiredArgsConstructor
public class FriendController {
    private final FriendService friends;

    /** 按公开编号、用户名或昵称搜索其他用户。 */
    @GetMapping("/search")
    public List<AuthViews.UserView> search(@RequestParam @NotBlank @Size(max = 32) String q) {
        return friends.search(q);
    }

    /** 返回本人好友和请求状态。 */
    @GetMapping
    public List<AuthViews.RequestView> list() {
        return friends.listRelations();
    }

    /** 发起申请，身份由当前登录决定。 */
    @PostMapping("/{userId}")
    public Map<String, String> add(@PathVariable @Positive Long userId) {
        friends.request(userId);
        return Map.of("message", "好友申请已发送");
    }

    /** 接收方处理申请。 */
    @PostMapping("/requests/{id}")
    public Map<String, String> respond(@PathVariable @Positive Long id, @RequestParam boolean accept) {
        friends.respond(id, accept);
        return Map.of("message", accept ? "已成为好友" : "已拒绝申请");
    }
}
