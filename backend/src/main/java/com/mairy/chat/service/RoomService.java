package com.mairy.chat.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.mairy.chat.dto.ChatViews.PageView;
import com.mairy.chat.dto.ChatViews.RoomView;
import com.mairy.chat.entity.Room;
import com.mairy.chat.mapper.RoomMapper;
import java.time.LocalDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 房间业务复用 MyBatis-Plus 3.5.18 的 Spring 服务实现。 */
@Service
public class RoomService extends ServiceImpl<RoomMapper, Room> {
    /** 分页读取房间，不在结果转换中查询数据库。 */
    public PageView<RoomView> listRooms(long page, long size) {
        Page<Room> result = lambdaQuery().orderByDesc(Room::getId).page(new Page<>(page, size));
        return new PageView<>(result.getRecords().stream().map(this::toView).toList(),
                result.getTotal(), page, size);
    }

    /** 创建房间并返回页面所需信息。 */
    @Transactional
    public RoomView createRoom(String name) {
        Room room = new Room();
        room.setName(name.strip());
        room.setCreatedAt(LocalDateTime.now());
        save(room);
        return toView(room);
    }

    /** 校验房间存在，供进房流程复用。 */
    public Room requireRoom(Long id) {
        Room room = getById(id);
        if (room == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "房间不存在");
        }
        return room;
    }

    /** 将实体转换成不依赖数据库结构的响应。 */
    private RoomView toView(Room room) {
        return new RoomView(room.getId().toString(), room.getName(), room.getCreatedAt());
    }
}
