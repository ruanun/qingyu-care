package com.qingyu.care.config;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * SSRF 守卫：工具调用由平台代租户出站，必须阻止租户把工具 URL 指向内网/环回/云元数据
 * （如 169.254.169.254）借平台身份探测或窃取凭据。发请求前对解析出的每个 IP 做校验。
 *
 * @author ruan
 */
public final class SsrfGuard {

    private static final Set<String> HTTP_SCHEMES = Set.of("http", "https");

    private SsrfGuard() {
    }

    /** 校验 URL 目标地址均为公网可达，否则抛 {@link IllegalArgumentException}。 */
    public static void assertAllowed(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("非法工具 URL: " + url, e);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("工具 URL 缺少合法主机: " + url);
        }
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (isBlocked(addr)) {
                    throw new IllegalArgumentException("工具 URL 指向内网/环回地址，已拒绝: " + host);
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("工具 URL 主机无法解析: " + host, e);
        }
    }

    /**
     * 解析并校验 URL 形态：必须是 http/https 且带合法主机。不解析 DNS，供落库前的形态校验
     * 与出站前取 host 共用。
     */
    public static URI requireHttpUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("非法 URL: " + url, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !HTTP_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("URL 必须是 http 或 https: " + url);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("URL 缺少合法主机: " + url);
        }
        return uri;
    }

    /** {@link #requireHttpUrl} + {@link #assertAllowed}：协议、主机、解析出的全部地址都过一遍。 */
    public static void assertAllowedHttpUrl(String url) {
        requireHttpUrl(url);
        assertAllowed(url);
    }

    /**
     * 放开私网、但<b>永不放行云元数据</b>的校验，供 {@code allow-private-*} 开关使用。
     *
     * <p>真机冒烟发现（2026-09-03）：那两个开关是给「本地跑 Ollama」「集成测试的本地 Webhook 接收器」
     * 用的，它们都在环回或普通私网地址上；而原实现只做形态校验，等于把
     * {@code 169.254.169.254}（AWS/Azure/GCP）与 {@code 100.100.100.200}（阿里云）也一起放开——
     * dev profile 下实测可以把模型 endpoint 指到阿里云元数据并成功落库。云元数据段对自托管模型
     * 没有任何正当用途，任何开关都不该放行它。
     */
    public static void assertAllowedHttpUrlAllowingPrivate(String url) {
        URI uri = requireHttpUrl(url);
        try {
            for (InetAddress addr : InetAddress.getAllByName(uri.getHost())) {
                if (isCloudMetadata(addr)) {
                    throw new IllegalArgumentException("URL 指向云元数据地址，已拒绝: " + uri.getHost());
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("URL 主机无法解析: " + uri.getHost(), e);
        }
    }

    /**
     * 云厂商元数据地址：链路本地 169.254.0.0/16（169.254.169.254 是 AWS/Azure/GCP/DigitalOcean
     * 的通用入口，169.254.170.2 是 ECS 任务角色）、CGNAT 100.64.0.0/10（阿里云 100.100.100.200）、
     * IPv6 链路本地 fe80::/10 与 AWS 的 fd00:ec2::/64。
     */
    public static boolean isCloudMetadata(InetAddress a) {
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int b0 = b[0] & 0xff;
            int b1 = b[1] & 0xff;
            return (b0 == 169 && b1 == 254)                 // 169.254.0.0/16
                    || (b0 == 100 && (b1 & 0xc0) == 0x40);  // 100.64.0.0/10
        }
        if (b.length == 16) {
            if (a.isLinkLocalAddress()) {                    // fe80::/10
                return true;
            }
            // fd00:ec2::/64（AWS IPv6 元数据）
            return (b[0] & 0xff) == 0xfd && (b[1] & 0xff) == 0x00
                    && (b[2] & 0xff) == 0xec && (b[3] & 0xff) == 0x02;
        }
        return false;
    }

    /**
     * 解析并校验主机：返回校验通过的全部地址（供连接期 DNS 钉住复用，杜绝 rebinding——
     * 校验用的解析结果与实际连接用的必须是同一份，否则 TTL=0 域名可在两次解析间翻转 IP）。
     */
    public static InetAddress[] resolveAndValidate(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("工具 URL 缺少合法主机");
        }
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            for (InetAddress a : addrs) {
                if (isBlocked(a)) {
                    throw new IllegalArgumentException("工具 URL 指向内网/环回地址，已拒绝: " + host);
                }
            }
            return addrs;
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("工具 URL 主机无法解析: " + host, e);
        }
    }

    /**
     * 纯函数：判断地址是否属于应拒绝的内网/特殊网段。
     *
     * <p>Java 自带的 {@code isSiteLocalAddress()} 只认 10/8、172.16/12、192.168/16，下面几段
     * 都要自己补（安全审查 M4，2026-09-03）：
     * <ul>
     *   <li>100.64.0.0/10（CGNAT）——<b>阿里云 ECS 的元数据服务是 100.100.100.200</b>，本项目默认
     *       对接 DashScope，阿里云是最可能的部署目标；</li>
     *   <li>192.0.0.0/24（IETF 协议保留）、198.18.0.0/15（基准测试）、240.0.0.0/4（保留）；</li>
     *   <li>IPv6：fc00::/7（ULA，Java 只认已废弃的 fec0::/10）、64:ff9b::/96（NAT64，尾 32 位是
     *       IPv4，按 IPv4 规则再判一次）。IPv4 映射地址 {@code ::ffff:a.b.c.d} 由 Java 直接解析成
     *       {@code Inet4Address}，天然走 IPv4 分支。</li>
     * </ul>
     */
    static boolean isBlocked(InetAddress a) {
        if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isAnyLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = a.getAddress();
        if (bytes.length == 4) {
            return isBlockedIpv4(bytes);
        }
        if (bytes.length == 16) {
            // fc00::/7 唯一本地地址
            if ((bytes[0] & 0xfe) == 0xfc) {
                return true;
            }
            // 64:ff9b::/96 NAT64：前 96 位固定，尾 32 位是 IPv4
            if ((bytes[0] & 0xff) == 0x00 && (bytes[1] & 0xff) == 0x64
                    && (bytes[2] & 0xff) == 0xff && (bytes[3] & 0xff) == 0x9b) {
                boolean middleZero = true;
                for (int i = 4; i < 12; i++) {
                    if (bytes[i] != 0) {
                        middleZero = false;
                        break;
                    }
                }
                if (middleZero) {
                    byte[] v4 = {bytes[12], bytes[13], bytes[14], bytes[15]};
                    return isBlockedIpv4(v4) || isPrivateOrSpecialIpv4(v4);
                }
            }
        }
        return false;
    }

    private static boolean isBlockedIpv4(byte[] b) {
        int b0 = b[0] & 0xff;
        int b1 = b[1] & 0xff;
        // 100.64.0.0/10：第二字节 0100 00xx
        if (b0 == 100 && (b1 & 0xc0) == 0x40) {
            return true;
        }
        // 192.0.0.0/24
        if (b0 == 192 && b1 == 0 && (b[2] & 0xff) == 0) {
            return true;
        }
        // 198.18.0.0/15：第二字节 0001 001x
        if (b0 == 198 && (b1 & 0xfe) == 0x12) {
            return true;
        }
        // 240.0.0.0/4（含 255.255.255.255）
        return (b0 & 0xf0) == 0xf0;
    }

    /** NAT64 内嵌 IPv4 走不到 Java 的 isLoopback/isSiteLocal 判定，这里手工补 IPv4 的常规私网段。 */
    private static boolean isPrivateOrSpecialIpv4(byte[] b) {
        int b0 = b[0] & 0xff;
        int b1 = b[1] & 0xff;
        return b0 == 10
                || b0 == 127
                || (b0 == 172 && (b1 & 0xf0) == 0x10)
                || (b0 == 192 && b1 == 168)
                || (b0 == 169 && b1 == 254)
                || b0 == 0;
    }
}
