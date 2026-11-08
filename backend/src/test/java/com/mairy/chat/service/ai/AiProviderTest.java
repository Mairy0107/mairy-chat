package com.mairy.chat.service.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mairy.chat.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 条件注册与绑定校验走真实 Spring 容器，无模型密钥也能启动。 */
class AiProviderTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
            .withUserConfiguration(Providers.class);

    /** 缺省只创建 Spring AI 实现，且保留约定默认参数。 */
    @Test
    void defaultsToOneSpringAiGatewayWithoutKey() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(AiModelGateway.class).size());
            assertInstanceOf(SpringAiChatGateway.class, context.getBean(AiModelGateway.class));
            var properties = context.getBean(AiProperties.class);
            assertEquals("", properties.getApiKey());
            assertEquals("https://api.deepseek.com", properties.getBaseUrl());
            assertEquals("deepseek-chat", properties.getModel());
            assertEquals(0.3, properties.getTemperature());
            assertEquals(2048, properties.getMaxTokens());
            assertEquals(10000, properties.getConnectTimeout());
            assertEquals(45000, properties.getReadTimeout());
            assertEquals(120000, properties.getTotalTimeout());
            assertEquals(40, properties.getDailyQuota());
        });
    }

    /** 显式 provider 仅有一个实现，不允许注入歧义或隐式双路调用。 */
    @ParameterizedTest
    @ValueSource(strings = {"legacy", "spring-ai"})
    void selectsExactlyOneGateway(String provider) {
        runner.withPropertyValues("chat.ai.provider=" + provider).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(AiModelGateway.class).size());
            assertEquals(provider.equals("legacy") ? DeepSeekClient.class : SpringAiChatGateway.class,
                    context.getBean(AiModelGateway.class).getClass());
        });
    }

    /** 非法 provider、无穷温度和无界超时必须启动失败。 */
    @ParameterizedTest
    @ValueSource(strings = {"provider=other", "provider=", "temperature=NaN", "temperature=Infinity", "temperature=-0.1",
            "temperature=2.1", "max-tokens=0", "max-tokens=8193", "connect-timeout=0", "read-timeout=120001",
            "total-timeout=180001"})
    void invalidConfigurationFailsStartup(String setting) {
        runner.withPropertyValues("chat.ai." + setting).run(context -> assertNotNull(context.getStartupFailure()));
    }

    /** 使用产品实现和配置绑定，JSON 与生产保持同一入口类型。 */
    @Configuration(proxyBeanMethods = false)
    @Import({AiProperties.class, SpringAiChatGateway.class, DeepSeekClient.class})
    static class Providers {
        /** 仅提供请求模型所需的 JSON 组件。 */
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }
}
