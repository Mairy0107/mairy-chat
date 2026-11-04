package com.mairy.chat.controller;

import com.mairy.chat.dto.AiRequests;
import com.mairy.chat.dto.AiViews;
import com.mairy.chat.service.ai.AiService;
import com.mairy.chat.service.ai.AiStreamService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.io.IOException;
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

/** 助手只使用现有登录 Bearer，所有会话读写由服务层检查归属。 */
@Validated
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {
    private final AiService ai;
    private final AiStreamService streams;

    /** 返回可用性、今日剩余额度和未实现的 P1 状态。 */
    @GetMapping("/status")
    public AiViews.Status status() { return ai.status(); }

    /** 创建本人独享的空对话。 */
    @PostMapping("/threads")
    @ResponseStatus(HttpStatus.CREATED)
    public AiViews.ThreadView open() { return ai.open(); }

    /** 列出本人最近会话，未配置模型也能查阅历史。 */
    @GetMapping("/threads")
    public List<AiViews.ThreadView> threads() { return ai.threads(); }

    /** 按消息主键加载一页历史。 */
    @GetMapping("/threads/{threadId}/messages")
    public AiViews.MessagePage history(@PathVariable @Positive Long threadId,
                                      @RequestParam(required = false) @Positive Long beforeId,
                                      @RequestParam(defaultValue = "30") @Min(1) @Max(50) int size) {
        return ai.history(threadId, beforeId, size);
    }

    /** 先执行 REST 门禁，再同步输出 SSE；不接入房间或私信 WebSocket。 */
    @PostMapping("/threads/{threadId}/messages")
    public void send(@PathVariable @Positive Long threadId, @Valid @RequestBody AiRequests.SendMessage request,
                     HttpServletResponse response) throws IOException {
        streams.send(threadId, request.content(), response);
    }
}
