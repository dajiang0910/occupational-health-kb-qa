package com.ohkb.infra.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.EncoderBase;

/**
 * JSON 日志脱敏编码器（骨架）。
 * <p>
 * 对日志消息中的敏感信息做正则脱敏：
 * <ul>
 *   <li>手机号：13812345678 → 138****5678</li>
 *   <li>API Key：sk-ws-xxx... → sk-ws-****...xxx（前后各保留 6 位）</li>
 *   <li>身份证号：320***199001011234</li>
 *   <li>JWT Token：完全移除</li>
 * </ul>
 * <p>
 * 当前为骨架实现 — 开发阶段配置在 logback-spring.xml 中引用。
 * Phase 2 实现完整脱敏逻辑。
 */
public class SensitiveJsonEncoder extends EncoderBase<ILoggingEvent> {

    private static final String PHONE_REGEX = "(1[3-9]\\d)\\d{4}(\\d{4})";
    private static final String PHONE_REPLACEMENT = "$1****$2";

    @Override
    public byte[] encode(ILoggingEvent event) {
        String msg = event.getFormattedMessage();
        msg = msg.replaceAll(PHONE_REGEX, PHONE_REPLACEMENT);
        // TODO: API Key 脱敏、身份证脱敏、JWT 移除
        return (msg + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public byte[] headerBytes() {
        return null;
    }

    @Override
    public byte[] footerBytes() {
        return null;
    }
}
