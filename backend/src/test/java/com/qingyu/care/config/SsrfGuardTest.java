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

    @Test
    void resolveAndValidateRejectsInternalHost() {
        assertThatThrownBy(() -> SsrfGuard.resolveAndValidate("127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolveAndValidateReturnsAddressesForPublicHost() {
        InetAddress[] addrs = SsrfGuard.resolveAndValidate("8.8.8.8");
        assertThat(addrs).isNotEmpty();
    }

    @Test
    void blocksIpv6UniqueLocalAndAnyLocalAndMulticast() throws Exception {
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("fd00::1"))).isTrue();   // IPv6 ULA
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("0.0.0.0"))).isTrue();   // anyLocal
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("224.0.0.1"))).isTrue(); // multicast
    }
    // ===== 安全审查 M4（2026-09-03）：isSiteLocalAddress() 覆盖不到的保留网段 =====

    /**
     * 阿里云 ECS 的元数据服务是 100.100.100.200，落在 CGNAT 段 100.64.0.0/10——Java 的
     * isSiteLocalAddress() 只认 10/8、172.16/12、192.168/16，此前完全放行。
     */
    @Test
    void blocksCarrierGradeNatAndOtherReservedRanges() throws Exception {
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("100.64.0.1"))).isTrue();
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("100.100.100.200"))).isTrue(); // 阿里云元数据
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("100.127.255.254"))).isTrue();
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("192.0.0.1"))).isTrue();      // IETF 协议保留
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("198.18.0.1"))).isTrue();     // 基准测试段
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("240.0.0.1"))).isTrue();      // 保留段
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("64:ff9b::a00:1"))).isTrue(); // NAT64 → 10.0.0.1
        // 边界外仍放行
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("100.63.255.255"))).isFalse();
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("100.128.0.1"))).isFalse();
    }

    @Test
    void assertAllowedHttpUrlRejectsNonHttpScheme() {
        assertThatThrownBy(() -> SsrfGuard.assertAllowedHttpUrl("ftp://8.8.8.8/x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SsrfGuard.assertAllowedHttpUrl("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SsrfGuard.assertAllowedHttpUrl("http://127.0.0.1/x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> SsrfGuard.assertAllowedHttpUrl("https://8.8.8.8/x")).doesNotThrowAnyException();
    }
}
