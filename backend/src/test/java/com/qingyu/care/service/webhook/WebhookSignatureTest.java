package com.qingyu.care.service.webhook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureTest {

    private final WebhookSignature signature = new WebhookSignature();

    @Test
    void sign_producesStableSha256HexWithPrefix() {
        // 已知向量：HMAC-SHA256(key="secret", msg="hello") 的十六进制
        String result = signature.sign("secret", "hello");
        assertThat(result).isEqualTo(
                "sha256=88aab3ede8d3adf94d26ab90d3bafd4a2083070c3bcce9c014ee04a443847c0b");
    }

    @Test
    void sign_differentBodyDiffersSignature() {
        assertThat(signature.sign("secret", "a")).isNotEqualTo(signature.sign("secret", "b"));
    }
}
