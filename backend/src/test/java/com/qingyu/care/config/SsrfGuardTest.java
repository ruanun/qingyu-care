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
    // ===== 真机冒烟发现（2026-09-03）：放开私网时仍必须拒绝云元数据 =====

    /**
     * {@code allow-private-*} 开关是给「本地跑 Ollama / 本地 Webhook 接收器」用的，它们都在环回或
     * 私网地址上；<b>云元数据段永远没有正当用途</b>。此前放开开关等于连
     * {@code 169.254.169.254}（AWS/Azure/GCP）与 {@code 100.100.100.200}（阿里云）一起放开——
     * dev profile 下实测可以把模型 endpoint 指到阿里云元数据并成功落库。
     */
    @Test
    void isCloudMetadata识别各云厂商的元数据地址() throws Exception {
        assertThat(SsrfGuard.isCloudMetadata(InetAddress.getByName("169.254.169.254"))).isTrue();
        assertThat(SsrfGuard.isCloudMetadata(InetAddress.getByName("169.254.170.2"))).isTrue();   // ECS 任务角色
        assertThat(SsrfGuard.isCloudMetadata(InetAddress.getByName("100.100.100.200"))).isTrue(); // 阿里云
        assertThat(SsrfGuard.isCloudMetadata(InetAddress.getByName("fe80::1"))).isTrue();         // IPv6 链路本地
        // 本地自托管模型的正常地址不受影响
        assertThat(SsrfGuard.isCloudMetadata(InetAddress.getByName("127.0.0.1"))).isFalse();
        assertThat(SsrfGuard.isCloudMetadata(InetAddress.getByName("10.0.0.5"))).isFalse();
        assertThat(SsrfGuard.isCloudMetadata(InetAddress.getByName("192.168.1.10"))).isFalse();
        assertThat(SsrfGuard.isCloudMetadata(InetAddress.getByName("8.8.8.8"))).isFalse();
    }

    /** 变异实验：让 assertAllowedHttpUrlAllowingPrivate 只做形态校验，第一组断言转红。 */
    @Test
    void assertAllowedHttpUrlAllowingPrivate放行环回与私网但拒绝元数据() {
        assertThatThrownBy(() -> SsrfGuard.assertAllowedHttpUrlAllowingPrivate("http://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SsrfGuard.assertAllowedHttpUrlAllowingPrivate("http://100.100.100.200/v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SsrfGuard.assertAllowedHttpUrlAllowingPrivate("ftp://127.0.0.1/v1"))
                .isInstanceOf(IllegalArgumentException.class);
        // 本地 Ollama / 本地接收器照常放行
        assertThatCode(() -> SsrfGuard.assertAllowedHttpUrlAllowingPrivate("http://127.0.0.1:11434/v1"))
                .doesNotThrowAnyException();
        assertThatCode(() -> SsrfGuard.assertAllowedHttpUrlAllowingPrivate("http://10.0.0.5:8080/hook"))
                .doesNotThrowAnyException();
    }

    /** 2026-09-30 安全审查补的网段：Java 自带判定都不认，或内嵌 IPv4 走不到 IPv4 分支 */
    @org.junit.jupiter.api.Test
    void 补充网段被拦截() throws Exception {
        for (String a : new String[]{
                "0.1.2.3",                 // 0.0.0.0/8
                "168.63.129.16",           // Azure WireServer
                "::127.0.0.1",             // IPv4 兼容地址
                "::10.0.0.5",
                "64:ff9b:1::a00:5",        // 本地 NAT64
                "2002:a00:5::1",           // 6to4 内嵌 10.0.0.5
                "2002:7f00:1::1"}) {       // 6to4 内嵌 127.0.0.1
            assertThat(SsrfGuard.isBlocked(InetAddress.getByName(a))).as(a).isTrue();
        }
        // 正常公网 IPv6 / 6to4 内嵌公网地址不误伤
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("2606:4700:4700::1111"))).isFalse();
        assertThat(SsrfGuard.isBlocked(InetAddress.getByName("2002:808:808::1"))).isFalse();
    }

    @org.junit.jupiter.api.Test
    void 放开私网时返回校验过的那一份地址_元数据仍拒() {
        assertThat(SsrfGuard.resolveAllowingPrivate("http://127.0.0.1:9/x"))
                .extracting(InetAddress::getHostAddress).containsExactly("127.0.0.1");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> SsrfGuard.resolveAllowingPrivate("http://169.254.169.254/latest"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("元数据");
    }
}
