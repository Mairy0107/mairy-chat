package com.mairy.chat.service.ai;

import static com.mairy.chat.service.ai.UpstreamSseGuardTest.frame;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mairy.chat.config.AiProperties;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/** 官方模型真实序列化、WebClient 与本地 TCP 流测试，绝不调用真实模型。 */
class SpringAiChatGatewayTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final List<Map<String, String>> MESSAGES = List.of(
            Map.of("role", "system", "content", "服务端系统提示"),
            Map.of("role", "user", "content", "历史问题"),
            Map.of("role", "assistant", "content", "历史回答"),
            Map.of("role", "user", "content", "当前问题"));

    /** 真实 HTTP 请求只发一次，路径、凭证、参数和三种角色由 SDK 正确编码。 */
    @Test
    void officialModelSerializesAndDeliversImmediately() throws Exception {
        var requests = new AtomicInteger();
        var body = new AtomicReference<String>();
        var path = new AtomicReference<String>();
        var auth = new AtomicReference<String>();
        var firstDelivered = new CountDownLatch(1);
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) -> {
            requests.incrementAndGet();
            path.set(request.uri());
            auth.set(request.requestHeaders().get("Authorization"));
            return request.receive().aggregate().asString().flatMap(input -> {
                body.set(input);
                String first = ": ping\r\n\r\n" + frame("{\"role\":\"assistant\",\"content\":null}", "null")
                        + frame("{\"reasoning_content\":\"不得转发\",\"content\":\"你好🌿\"}", "null")
                        .replace(",\"choices\"", ",\ndata: \"choices\"").replace("\n", "\r\n");
                // 首增量必须在后续结束帧之前交付，禁止整流收集。
                Flux<String> tail = Mono.fromCallable(() -> {
                    assertTrue(firstDelivered.await(3, TimeUnit.SECONDS), "首增量未及时交付");
                    return frame("{}", "\"stop\"") + "data: [DONE]";
                }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).flux();
                byte[] bytes = first.getBytes(StandardCharsets.UTF_8);
                Flux<io.netty.buffer.ByteBuf> fragments = Flux.range(0, bytes.length)
                        .map(index -> Unpooled.wrappedBuffer(new byte[] {bytes[index]}));
                return response.header("Content-Type", "text/event-stream")
                        .send(fragments.concatWith(tail.map(value -> Unpooled.copiedBuffer(value, StandardCharsets.UTF_8))))
                        .then();
            });
        }).bindNow();
        var gateway = gateway(server.port(), 3000, 6000);
        Thread caller = Thread.currentThread();
        try {
            var text = new StringBuilder();
            gateway.stream(MESSAGES, value -> {
                assertSame(caller, Thread.currentThread());
                text.append(value);
                firstDelivered.countDown();
            });
            assertEquals("你好🌿", text.toString());
            assertEquals(1, requests.get());
            assertEquals("/chat/completions", path.get());
            assertEquals("Bearer fixture-key", auth.get());
            var payload = json.readTree(body.get());
            assertEquals("deepseek-chat", payload.path("model").asText());
            assertEquals(0.3, payload.path("temperature").asDouble());
            assertEquals(2048, payload.path("max_tokens").asInt());
            assertTrue(payload.path("stream").asBoolean());
            assertEquals(json.valueToTree(MESSAGES), payload.path("messages"));
            assertFalse(payload.hasNonNull("tools"));
        } finally { gateway.destroy(); server.disposeNow(); }
    }

    /** SDK 过滤 DONE 并取消后仍能成功；null 结束、STOP、LENGTH 都要求非空正文。 */
    @ParameterizedTest
    @ValueSource(strings = {"null", "\"stop\"", "\"length\""})
    void safeFinishWithActualDone(String finish) throws Exception {
        withFake(frame("{\"content\":\"安全正文\"}", finish) + "data: [DONE]\n\n", gateway -> {
            var text = new StringBuilder();
            gateway.stream(MESSAGES, text::append);
            assertEquals("安全正文", text.toString());
        });
    }

    /** 无效上游不能把部分文本确认为完整，错误不得带入原始响应。 */
    @ParameterizedTest
    @ValueSource(strings = {"truncated", "malformed", "error", "empty", "null", "reasoning", "blank", "oversize",
            "tool", "finish", "trailing-json"})
    void invalidOrIncompleteResponsesFail(String kind) throws Exception {
        String text = frame("{\"content\":\"部分正文\"}", "null");
        String input = switch (kind) {
            case "truncated" -> text;
            case "malformed" -> text + "data: {broken\n\n";
            case "error" -> text + "data: {\"error\":{\"message\":\"fixture-secret\"}}\n\n";
            case "empty" -> "data: {\"id\":\"fixture-1\",\"choices\":[]}\n\n";
            case "null" -> "data: {\"id\":\"fixture-1\",\"choices\":null}\n\n";
            case "reasoning" -> frame("{\"reasoning_content\":\"不可转发\"}", "\"stop\"");
            case "blank" -> frame("{\"content\":\"  \"}", "\"stop\"");
            case "oversize" -> frame("{\"content\":\"" + "x".repeat(16001) + "\"}", "null");
            case "tool" -> frame("{\"tool_calls\":[{\"index\":0,\"id\":\"tool-1\",\"type\":\"function\","
                    + "\"function\":{\"name\":\"never_execute\",\"arguments\":\"{}\"}}]}", "null");
            case "trailing-json" -> text.replace("\n\n", " false\n\n");
            default -> frame("{\"content\":\"部分正文\"}", "\"unexpected\"");
        };
        if (!kind.equals("truncated")) input += "data: [DONE]\n\n";
        withFake(input, gateway -> {
            var error = assertThrows(IOException.class, () -> gateway.stream(MESSAGES, ignored -> {}));
            assertFalse(error.toString().contains("fixture-secret"));
            assertNull(error.getCause());
        });
    }

    /** 上游审核结束必须沿用业务 blocked 分类。 */
    @Test
    void contentFilterIsBlocked() throws Exception {
        withFake(frame("{\"content\":null}", "\"content_filter\"") + "data: [DONE]\n\n", gateway ->
                assertThrows(SensitiveWordFilter.BlockedOutput.class, () -> gateway.stream(MESSAGES, ignored -> {})));
    }

    /** HTTP 错误只发一次请求，状态体不泄漏，也不运行时回退。 */
    @ParameterizedTest
    @ValueSource(ints = {401, 429, 500})
    void httpErrorsNeverRetry(int status) throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) -> {
            requests.incrementAndGet();
            return response.status(status).sendString(Mono.just("fixture-secret"));
        }).bindNow();
        var gateway = gateway(server.port(), 1000, 2000);
        try {
            var error = assertThrows(IOException.class, () -> gateway.stream(MESSAGES, ignored -> {}));
            assertNull(error.getCause());
            assertFalse(error.toString().contains("fixture-secret"));
            assertEquals(1, requests.get());
        } finally { gateway.destroy(); server.disposeNow(); }
    }

    /** 静默流受读取时限约束，持续心跳仍受绝对总时限约束且取消 TCP 流。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void socketReadAndAbsoluteTimeoutCancel(boolean heartbeat) throws Exception {
        var closed = new CountDownLatch(1);
        var server = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) -> {
            request.withConnection(connection -> connection.onDispose(closed::countDown));
            Flux<String> input = heartbeat ? Flux.interval(Duration.ofMillis(25)).map(index -> ": ping\n\n") : Flux.never();
            return response.header("Content-Type", "text/event-stream").sendString(input);
        }).bindNow();
        var gateway = gateway(server.port(), heartbeat ? 1500 : 200, heartbeat ? 400 : 2500);
        try {
            long started = System.nanoTime();
            assertThrows(IOException.class, () -> gateway.stream(MESSAGES, ignored -> {}));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 2400);
            assertTrue(closed.await(3, TimeUnit.SECONDS));
        } finally { gateway.destroy(); server.disposeNow(); }
    }

    /** 回调断连或过滤异常必须关闭订阅，重复失败不会耗尽连接池。 */
    @Test
    void callbackAndFilterCancelRepeatedSocketStreams() throws Exception {
        var closed = new AtomicInteger();
        var server = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) -> {
            request.withConnection(connection -> connection.onDispose(closed::incrementAndGet));
            return response.header("Content-Type", "text/event-stream").sendString(Flux.concat(
                    Mono.just(frame("{\"content\":\"敏感\"}", "null")), Flux.never()));
        }).bindNow();
        var gateway = gateway(server.port(), 1000, 2500);
        try {
            for (int index = 0; index < 12; index++) {
                if (index % 2 == 0) assertThrows(IOException.class,
                        () -> gateway.stream(MESSAGES, ignored -> { throw new IOException("客户端断开"); }));
                else assertThrows(SensitiveWordFilter.BlockedOutput.class,
                        () -> gateway.stream(MESSAGES, ignored -> { throw new SensitiveWordFilter.BlockedOutput(); }));
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (closed.get() < 12 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(12, closed.get());
        } finally { gateway.destroy(); server.disposeNow(); }
    }

    /** 本地拒绝连接必须有限失败，禁止通过公网地址制造连接超时。 */
    @Test
    void refusedConnectionIsSanitized() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var gateway = gateway(port, 300, 1000);
        try { assertThrows(IOException.class, () -> gateway.stream(MESSAGES, ignored -> {})); }
        finally { gateway.destroy(); }
    }

    /** 每次 fake exchange 只允许一个订阅，仍经过真实 WebClient 和官方模型。 */
    private void withFake(String input, GatewayCheck check) throws Exception {
        var gateway = gateway(1, 1000, 2000);
        var subscriptions = new AtomicInteger();
        var builder = WebClient.builder().exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE).body(Flux.defer(() -> {
                    assertEquals(1, subscriptions.incrementAndGet());
                    return Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(input.getBytes(StandardCharsets.UTF_8)));
                })).build()));
        ReflectionTestUtils.setField(gateway, "webClient", builder);
        try { check.run(gateway); assertEquals(1, subscriptions.get()); }
        finally { gateway.destroy(); }
    }

    /** 使用仅供本测试的密钥与回环端口，超时缩短以获得稳定反馈。 */
    private SpringAiChatGateway gateway(int port, int readTimeout, int totalTimeout) {
        var properties = new AiProperties();
        properties.setApiKey("fixture-key");
        properties.setBaseUrl("http://127.0.0.1:" + port);
        properties.setConnectTimeout(300);
        properties.setReadTimeout(readTimeout);
        properties.setTotalTimeout(totalTimeout);
        return new SpringAiChatGateway(properties, json);
    }

    /** 允许各协议用例断言有检查异常的阻塞调用。 */
    private interface GatewayCheck { void run(SpringAiChatGateway gateway) throws Exception; }
}
