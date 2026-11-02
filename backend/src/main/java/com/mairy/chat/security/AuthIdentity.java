package com.mairy.chat.security;

import com.mairy.chat.entity.LoginSession;
import com.mairy.chat.entity.User;

/** 当前请求已校验的账号与登录会话，仅在服务端使用。 */
public record AuthIdentity(User user, LoginSession session) { }
