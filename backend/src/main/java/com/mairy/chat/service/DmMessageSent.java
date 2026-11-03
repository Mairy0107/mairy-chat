package com.mairy.chat.service;

import com.mairy.chat.dto.DmViews;

/** 私信保存事件，仅在事务提交后向双方推送。 */
public record DmMessageSent(Long userLow, Long userHigh, DmViews.MessageView message) { }
