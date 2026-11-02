package com.mairy.chat.controller;

import com.mairy.chat.dto.ChatRequests.SendMessage;
import com.mairy.chat.dto.ChatViews.MessagePage;
import com.mairy.chat.dto.ChatViews.MessageView;
import com.mairy.chat.service.MessageService;
import com.mairy.chat.websocket.ChatSocketHandler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/rooms/{roomId}/messages")
@RequiredArgsConstructor
public class MessageController {
    private final MessageService messageService;
    private final ChatSocketHandler socketHandler;

    /** 校验会话后按游标读取历史消息。 */
    @GetMapping
    public MessagePage history(@PathVariable @Positive Long roomId,
                                @RequestHeader(value = "X-Session-Token", required = false) String token,
                                @RequestParam(required = false) @Positive Long beforeId,
                                @RequestParam(defaultValue = "30") @Min(1) @Max(50) int size) {
        return messageService.history(roomId, token, beforeId, size);
    }

    /** 消息提交成功后推送到当前房间，断线客户端可通过历史接口补读。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MessageView send(@PathVariable @Positive Long roomId,
                            @RequestHeader(value = "X-Session-Token", required = false) String token,
                            @Valid @RequestBody SendMessage request) {
        MessageView message = messageService.send(roomId, token, request);
        socketHandler.publish(roomId, message);
        return message;
    }
}
