package com.mairy.chat.controller;

import com.mairy.chat.dto.ChatRequests.JoinRoom;
import com.mairy.chat.dto.ChatViews.MemberView;
import com.mairy.chat.service.MemberService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/rooms/{roomId}/members")
@RequiredArgsConstructor
public class MemberController {
    private final MemberService memberService;

    /** 使用昵称进房并返回匿名会话凭证。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MemberView join(@PathVariable @Positive Long roomId,
                           @Valid @RequestBody JoinRoom request) {
        return memberService.joinRoom(roomId, request.nickname());
    }
}
