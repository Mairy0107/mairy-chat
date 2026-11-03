package com.mairy.chat.controller;

import com.mairy.chat.dto.DmRequests;
import com.mairy.chat.dto.DmViews;
import com.mairy.chat.service.DmService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 所有私信接口使用登录 Bearer，独立于进房凭证。 */
@Validated
@RestController
@RequestMapping("/api/dm")
@RequiredArgsConstructor
public class DmController {
    private final DmService dm;

    /** 获取或创建与指定好友的唯一会话。 */
    @PostMapping("/with/{userId}")
    public DmViews.ThreadView open(@PathVariable @Positive Long userId) {
        return dm.open(userId);
    }

    /** 返回本人可访问的最近200个会话。 */
    @GetMapping("/threads")
    public List<DmViews.ThreadView> threads() {
        return dm.threads();
    }

    /** 仅允许仍为好友的参与人读取游标历史。 */
    @GetMapping("/threads/{threadId}/messages")
    public DmViews.MessagePage history(@PathVariable @Positive Long threadId,
                                       @RequestParam(required = false) @Positive Long beforeId,
                                       @RequestParam(defaultValue = "30") @Min(1) @Max(50) int size) {
        return dm.history(threadId, beforeId, size);
    }

    /** 保存事务提交后再推送，断线客户端可通过历史补读。 */
    @PostMapping("/threads/{threadId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public DmViews.MessageView send(@PathVariable @Positive Long threadId,
                                    @Valid @RequestBody DmRequests.SendMessage request) {
        return dm.send(threadId, request);
    }
}
