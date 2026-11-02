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

    /** 返回当前登录用户的自建房，未创建时返回空响应。 */
    @GetMapping("/mine")
    public RoomView mine() {
        return roomService.mine();
    }

    /** 按主键读取房间资料供路由刷新使用。 */
    @GetMapping("/{id}")
    public RoomView detail(@org.springframework.web.bind.annotation.PathVariable @jakarta.validation.constraints.Positive Long id) {
        return roomService.detail(id);
    }

    /** 创建当前账号唯一的房间。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoomView create(@Valid @RequestBody CreateRoom request) {
        return roomService.createRoom(request.name());
    }
}
