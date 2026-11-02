package com.mairy.chat.controller;

import com.mairy.chat.dto.E2eeRequests;
import com.mairy.chat.dto.E2eeViews;
import com.mairy.chat.service.E2eeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 公钥及信封接口完全独立于管理员流程，没有解密接口。 */
@Validated
@RestController
@RequestMapping("/api/e2ee")
@RequiredArgsConstructor
public class E2eeController {
    private final E2eeService service;

    /** 读取当前账号的公钥。 */
    @GetMapping("/identity")
    public E2eeViews.PublicKeyView identity() {
        return service.mine();
    }

    /** 首次发布客户端生成的公钥。 */
    @PutMapping("/identity")
    public E2eeViews.PublicKeyView publish(@Valid @RequestBody E2eeRequests.PublishKey request) {
        return service.publish(request.publicKey());
    }

    /** 读取当前普通成员自己的信封。 */
    @GetMapping("/rooms/{roomId}")
    public E2eeViews.KeyState state(@PathVariable @Positive Long roomId,
                                    @RequestHeader("X-Session-Token") String token) {
        return service.state(roomId, token);
    }

    /** 房主查看需要本人核验指纹的候选成员。 */
    @GetMapping("/rooms/{roomId}/candidates")
    public List<E2eeViews.Candidate> candidates(@PathVariable @Positive Long roomId,
                                               @RequestHeader("X-Session-Token") String token) {
        return service.candidates(roomId, token);
    }

    /** 房主提交只针对目标普通成员的密钥信封。 */
    @PutMapping("/rooms/{roomId}/members/{userId}")
    public void grant(@PathVariable @Positive Long roomId, @PathVariable @Positive Long userId,
                       @RequestHeader("X-Session-Token") String token,
                       @Valid @RequestBody E2eeRequests.GrantKey request) {
        service.grant(roomId, token, userId, request);
    }
}
