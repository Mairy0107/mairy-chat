package com.mairy.chat;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@MapperScan("com.mairy.chat.mapper")
public class ChatApplication {

    /** 启动聊天室后端。 */
    public static void main(String[] args) {
        SpringApplication.run(ChatApplication.class, args);
    }
}
