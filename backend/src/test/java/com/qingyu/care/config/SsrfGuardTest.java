package com.qingyu.care.config;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import static org.assertj.core.api.Assertions.*;

class SsrfGuardTest {

    @Test
    void blocksLoopbackLinkLocalAndSiteLocal() throws Exception {
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("127.0.0.1"))).isTrue();
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("169.254.169.254"))).isTrue(); // 云元数据
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("10.0.0.1"))).isTrue();
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("192.168.1.1"))).isTrue();
    }

    @Test
    void allowsPublicAddress() throws Exception {
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("8.8.8.8"))).isFalse();
    }

    @Test
    void assertAllowedRejectsInternalUrl() {
        assertThatThrownBy(() -> SsrfGuard.assertAllowed("http://127.0.0.1/orders/1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assertAllowedPassesPublicUrl() {
        assertThatCode(() -> SsrfGuard.assertAllowed("http://8.8.8.8/orders/1"))
                .doesNotThrowAnyException();
    }

    @Test
    void assertAllowedRejectsHostlessUrl() {
        assertThatThrownBy(() -> SsrfGuard.assertAllowed("not-a-url"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
