package com.mairy.chat.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mairy.chat.config.AiProperties;
import io.netty.channel.ChannelOption;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;

/** 手动构造官方 DeepSeek 模型；无密钥可启动，网络资源复用而协议状态逐请求隔离。 */
@Component
@ConditionalOnProperty(prefix = "chat.ai", name = "provider", havingValue = "spring-ai", matchIfMissing = true)
public class SpringAiChatGateway implements AiModelGateway, DisposableBean {
    private final AiProperties properties;
    private final ObjectMapper json;
    private final ConnectionProvider pool;
    private final LoopResources loops;
    private final WebClient.Builder webClient;

    /** 独立有限连接池禁用断连自动重放、重定向和 wiretap，保持每轮至多一次 POST。 */
    public SpringAiChatGateway(AiProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        pool = ConnectionProvider.builder("ai-upstream").maxConnections(8).pendingAcquireMaxCount(8)
                .pendingAcquireTimeout(Duration.ofMillis(properties.getConnectTimeout())).build();
        loops = LoopResources.create("ai-upstream", 2, true);
        HttpClient http = HttpClient.create(pool).runOn(loops).disableRetry(true).followRedirect(false).wiretap(false)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.getConnectTimeout())
                .responseTimeout(Duration.ofMillis(properties.getReadTimeout()));
        webClient = WebClient.builder().clientConnector(new ReactorClientHttpConnector(http))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(262144))
                .defaultStatusHandler(HttpStatusCode::isError, response -> response.releaseBody()
                        .thenReturn(new IOException("上游请求失败")));
    }

    /** MVC 线程有界消费并取消上游，协议失败统一为安全 IO 错误，拦截保留业务分类。 */
    @Override
    public void stream(List<Map<String, String>> messages, DeltaConsumer consumer) throws IOException {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) throw new IOException("助手未配置");
        List<Message> prompt = messages.stream().map(this::message).toList();
        var guard = new UpstreamSseGuard(json);
        var cancellation = Sinks.<Void>empty();
        long deadline = System.nanoTime() + Duration.ofMillis(properties.getTotalTimeout()).toNanos();
        var api = DeepSeekApi.builder().baseUrl(properties.getBaseUrl().replaceAll("/+$", ""))
                .completionsPath("/chat/completions").apiKey(properties.getApiKey())
                .restClientBuilder(RestClient.builder()).webClientBuilder(guardedClient(guard, cancellation.asMono())).build();
        var options = DeepSeekChatOptions.builder().model(properties.getModel()).temperature(properties.getTemperature())
                .maxTokens(properties.getMaxTokens()).internalToolExecutionEnabled(false).build();
        var model = DeepSeekChatModel.builder().deepSeekApi(api).defaultOptions(options)
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
        // 每次订阅按同一绝对截止时间计算，持续增量不能延长预算。
        Mono<Long> timeout = Mono.defer(() -> Mono.delay(Duration.ofNanos(Math.max(1, deadline - System.nanoTime()))));
        int length = 0;
        boolean nonblank = false;
        try (var stream = model.stream(new Prompt(prompt)).timeout(timeout, ignored -> timeout,
                Flux.error(new IOException("上游总时限已到"))).toStream(1)) {
            var iterator = stream.iterator();
            while (iterator.hasNext()) {
                checkDeadline(deadline);
                String text = content(iterator.next());
                if (text == null || text.isEmpty()) continue;
                length += text.length();
                if (length > 16000) throw new IOException("回复过长");
                nonblank |= !text.isBlank();
                consumer.accept(text);
                checkDeadline(deadline);
            }
            guard.requireComplete();
            if (!nonblank) throw new IOException("空回复");
        } catch (SensitiveWordFilter.BlockedOutput exception) {
            throw exception;
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // 恢复可安全判定的拦截与总超时，其余错误不保留上游正文异常链。
            guard.checkBlocked();
            checkDeadline(deadline);
            throw new IOException("上游调用失败");
        } finally {
            // SDK 内部窗口可能延迟传递取消，调用结束时直接终止原始响应订阅。
            cancellation.tryEmitEmpty();
        }
    }

    /** 单次订阅原始 body 并监听请求结束信号，释放失败或取消中的网络流。 */
    private WebClient.Builder guardedClient(UpstreamSseGuard guard, Mono<Void> cancellation) {
        return webClient.clone().filter((request, next) -> next.exchange(request).map(response -> {
            var subscribed = new AtomicBoolean();
            // 使用变换重载，避免替换 body 时额外订阅并释放原始响应流。
            return response.mutate().body(body -> Flux.defer(() -> {
                // WebClient 包装读取错误时可能再次 releaseBody，不能重读单次响应。
                if (!subscribed.compareAndSet(false, true)) return Flux.empty();
                return body.<DataBuffer>handle((buffer, sink) -> {
                    try { guard.observe(buffer); sink.next(buffer); }
                    catch (RuntimeException exception) { DataBufferUtils.release(buffer); sink.error(exception); }
                }).doOnComplete(guard::end).takeUntilOther(cancellation);
            })).build();
        }));
    }

    /** 严格映射服务端三种角色，拒绝工具与其他输入角色。 */
    private Message message(Map<String, String> value) {
        if (value == null || value.get("role") == null || value.get("content") == null) {
            throw new IllegalArgumentException("无效模型消息");
        }
        return switch (value.get("role")) {
            case "system" -> new SystemMessage(value.get("content"));
            case "user" -> new UserMessage(value.get("content"));
            case "assistant" -> new AssistantMessage(value.get("content"));
            default -> throw new IllegalArgumentException("不支持的模型角色");
        };
    }

    /** 只读取普通正文和安全结束元数据，不输出 reasoning 或执行工具。 */
    private String content(ChatResponse response) throws IOException {
        if (response == null || response.getResults() == null || response.getResults().size() != 1
                || response.getResult() == null || response.getResult().getOutput() == null) throw new IOException("无效模型回复");
        var result = response.getResult();
        if (!result.getOutput().getToolCalls().isEmpty()) throw new IOException("不支持工具调用");
        String finish = result.getMetadata() == null ? null : result.getMetadata().getFinishReason();
        if ("CONTENT_FILTER".equalsIgnoreCase(finish)) throw new SensitiveWordFilter.BlockedOutput();
        if (finish != null && !finish.isBlank() && !"STOP".equalsIgnoreCase(finish) && !"LENGTH".equalsIgnoreCase(finish)) {
            throw new IOException("无效模型结束原因");
        }
        return result.getOutput().getText();
    }

    /** 阻塞回调返回后再次检查绝对时限；不能主动打断 Servlet 已阻塞的写入。 */
    private void checkDeadline(long deadline) throws IOException {
        if (System.nanoTime() >= deadline) throw new IOException("上游总时限已到");
    }

    /** 应用关闭时释放本组件拥有的连接池和事件线程。 */
    @Override
    public void destroy() {
        pool.disposeLater().block(Duration.ofSeconds(5));
        loops.disposeLater(Duration.ZERO, Duration.ofSeconds(5)).block(Duration.ofSeconds(6));
    }
}
