package com.mairy.chat.service.ai;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** 模型调用边界：阻塞到完整结束或失败，调用方在 MVC 线程处理安全增量。 */
public interface AiModelGateway {
    /** 仅接收服务端构造的上下文，回调失败必须取消上游且不得重试。 */
    void stream(List<Map<String, String>> messages, DeltaConsumer consumer) throws IOException;

    /** 允许输出过滤或 Servlet 写入失败直接中止本轮。 */
    @FunctionalInterface
    interface DeltaConsumer {
        /** 消费普通文本增量，不包含 reasoning 或工具结果。 */
        void accept(String content) throws IOException;
    }
}
