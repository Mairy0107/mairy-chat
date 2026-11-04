package com.mairy.chat.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 助手配置不生成 toString，避免凭证被意外写入日志。 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "chat.ai")
public class AiProperties {
    private String apiKey = "";
    private String baseUrl = "https://api.deepseek.com";
    private String model = "deepseek-chat";
    @Min(0) @Max(10000)
    private int dailyQuota = 40;
    @Size(max = 256)
    private List<@Size(max = 128) String> sensitiveWords = new ArrayList<>();
}
