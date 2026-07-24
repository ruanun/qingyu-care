package com.qingyu.care.config;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * SSRF 守卫：工具调用由平台代租户出站，必须阻止租户把工具 URL 指向内网/环回/云元数据
 * （如 169.254.169.254）借平台身份探测或窃取凭据。发请求前对解析出的每个 IP 做校验。
 *
 * @author ruan
 */
public final class SsrfGuard {

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

    /** 纯函数：判断地址是否属于应拒绝的内网/特殊网段。 */
    static boolean isBlocked(InetAddress a) {
        if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isAnyLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        // IPv6 唯一本地地址 ULA（fc00::/7，RFC 4193）：Java 的 isSiteLocalAddress() 对 IPv6
        // 仅识别已废弃的 fec0::/10，不覆盖 fc00::/7，须按首字节补判，防内网 IPv6 绕过。
        byte[] bytes = a.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }
}
