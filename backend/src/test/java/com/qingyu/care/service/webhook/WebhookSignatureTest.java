package com.qingyu.care.service.webhook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Webhook 签名覆盖「时间戳.请求体」而不只是请求体（安全审查 L6，2026-09-03）：
 * 只签请求体时，截获一次投递就能在任意时间重放，接收方无从按时间拒绝。
 * 接收方以同法比对，并要求 |now - timestamp| 在 5 分钟内、按载荷 id 去重。
 */
class WebhookSignatureTest {

    private final WebhookSignature signature = new WebhookSignature();

    @Test
    void sign_覆盖时间戳与请求体_已知向量() {
        // HMAC-SHA256(key="secret", msg="1700000000000.hello")
        String result = signature.sign("secret", 1700000000000L, "hello");
        assertThat(result).isEqualTo(
                "sha256=34e93906d48eb7d7b2bf2fee809e41149b8a3030b5459f29c5ca880f4cc626a6");
    }

    /** 变异实验：把签名消息改回只有 body，本条转红——不同时间戳会得到相同签名。 */
    @Test
    void sign_时间戳不同则签名不同() {
        assertThat(signature.sign("secret", 1L, "a")).isNotEqualTo(signature.sign("secret", 2L, "a"));
    }

    @Test
    void sign_请求体不同则签名不同() {
        assertThat(signature.sign("secret", 1L, "a")).isNotEqualTo(signature.sign("secret", 1L, "b"));
    }
}
