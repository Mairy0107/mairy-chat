package com.mairy.chat.controller;

import com.mairy.chat.dto.ChatRequests.CreateRoom;
import com.mairy.chat.dto.ChatViews.PageView;
import com.mairy.chat.dto.ChatViews.RoomView;
import com.mairy.chat.service.RoomService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomController {
    private final RoomService roomService;

    /** 返回指定页的房间列表。 */
    @GetMapping
    public PageView<RoomView> list(@RequestParam(defaultValue = "1") @Min(1) long page,
                                   @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size) {
        return roomService.listRooms(page, size);
    }

    /** 创建一个可供昵称加入的房间。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoomView create(@Valid @RequestBody CreateRoom request) {
        return roomService.createRoom(request.name());
    }
}
