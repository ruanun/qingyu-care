package com.qingyu.care.service.webhook;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Webhook 请求签名：HMAC-SHA256(secret, timestamp + "." + rawBody)，输出 "sha256=<hex>"。
 * 时间戳随 {@code X-Qingyu-Timestamp} 头一起发出（毫秒）。
 *
 * <p>为什么把时间戳签进去（安全审查 L6，2026-09-03）：只签请求体时，截获一次投递就能在任意时间
 * 重放，接收方无从按时间拒绝。对齐 Stripe 惯例。接收方以同法比对，并要求 |now - timestamp|
 * 在 5 分钟内、按载荷 {@code id}（投递 id，重试会复用同一个）去重——去重无论如何都要做，
 * 投递语义是至少一次。
 *
 * @author ruan
 */
@Component
public class WebhookSignature {

    public String sign(String secret, long timestampMillis, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal((timestampMillis + "." + body).getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(raw);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Webhook 签名计算失败", e);
        }
    }
}
