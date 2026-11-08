package com.mairy.chat.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
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
    @Pattern(regexp = "legacy|spring-ai")
    private String provider = "spring-ai";
    private String apiKey = "";
    private String baseUrl = "https://api.deepseek.com";
    private String model = "deepseek-chat";
    // 两种调用实现共用有限参数，避免无限等待或非有限采样值。
    private double temperature = 0.3;
    @Min(1) @Max(8192)
    private int maxTokens = 2048;
    @Min(1) @Max(120000)
    private int connectTimeout = 10000;
    @Min(1) @Max(120000)
    private int readTimeout = 45000;
    @Min(1) @Max(180000)
    private int totalTimeout = 120000;

    /** Bean Validation 显式拒绝 NaN、无穷大及非法温度。 */
    @AssertTrue(message = "temperature 必须是 0 到 2 的有限值")
    public boolean isTemperatureValid() {
        return Double.isFinite(temperature) && temperature >= 0 && temperature <= 2;
    }

    @Min(0) @Max(10000)
    private int dailyQuota = 40;
    @Size(max = 256)
    private List<@Size(max = 128) String> sensitiveWords = new ArrayList<>();
}
