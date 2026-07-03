package com.qingyu.care.service.webhook;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Webhook 请求体签名：HMAC-SHA256(secret, rawBody)，输出 "sha256=<hex>"。
 * 对齐 GitHub/Stripe 惯例，接收方以同法比对。
 *
 * @author ruan
 */
@Component
public class WebhookSignature {

    public String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(raw);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Webhook 签名计算失败", e);
        }
    }
}
