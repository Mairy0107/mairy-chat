package com.mairy.chat.service.ai;

import com.mairy.chat.config.AiProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** 基础字面词过滤，不记录词表或命中正文；不替代专业内容审核。 */
@Component
public class SensitiveWordFilter {
    public static final String REFUSAL = "这段内容暂时无法处理，请换个话题。";
    private final List<Pattern> patterns;
    private final int holdback;

    /** 合并资源与配置词表，忽略空行和以井号开头的注释。 */
    public SensitiveWordFilter(AiProperties properties) throws IOException {
        String resource = new ClassPathResource("sensitive-words.txt").getContentAsString(StandardCharsets.UTF_8);
        List<String> words = Stream.concat(resource.lines(), properties.getSensitiveWords().stream())
                .filter(word -> word != null).map(String::strip)
                .filter(word -> !word.isEmpty() && !word.startsWith("#")).distinct().toList();
        patterns = words.stream().map(word -> Pattern.compile(Pattern.quote(word),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)).toList();
        holdback = Math.max(0, words.stream().mapToInt(String::length).max().orElse(1) - 1);
    }

    /** 不向调用方返回具体命中的词语。 */
    public boolean blocked(String content) {
        return patterns.stream().anyMatch(pattern -> pattern.matcher(content).find());
    }

    /** 为每条流创建独立尾部缓冲，避免敏感词跨分片泄漏。 */
    public Buffer buffer() {
        return new Buffer();
    }

    public final class Buffer {
        private final StringBuilder text = new StringBuilder();
        private int sent;

        /** 检查累计正文后只放行安全前缀，保留最长词长减一的尾部。 */
        public String append(String delta) {
            if (text.length() + delta.length() > 16000) throw new IllegalStateException("回复过长");
            int checkFrom = Math.max(0, text.length() - holdback);
            text.append(delta);
            // 已放行前缀无需重复扫描，只检查旧尾部与新分片；完成后再全量复查。
            if (blocked(text.substring(checkFrom))) throw new BlockedOutput();
            int end = Math.max(sent, text.length() - holdback);
            if (end > sent && Character.isHighSurrogate(text.charAt(end - 1))) --end;
            String ready = text.substring(sent, end);
            sent = end;
            return ready;
        }

        /** 流完整结束才放行最后一段；最终正文再次检查。 */
        public String finish() {
            if (blocked(text.toString())) throw new BlockedOutput();
            String tail = text.substring(sent);
            sent = text.length();
            return tail;
        }

        /** 取得最终可入库正文，不在流循环中访问数据库。 */
        public String content() {
            return text.toString();
        }
    }

    /** 内部仅携带事件类型，不携带原始模型输出。 */
    public static final class BlockedOutput extends RuntimeException { }
}
