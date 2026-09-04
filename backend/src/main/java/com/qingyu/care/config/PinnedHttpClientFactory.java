package com.qingyu.care.config;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 出站 HttpClient 的统一构造：<b>钉住 DNS + 硬关重定向 + 三段超时</b>。
 *
 * <p>平台替租户出站的路径有五条（HTTP 工具、网页抓取/robots、MCP、OpenAPI schema、Webhook）。
 * 前两条各自手写过一份一模一样的构造，第五条则干脆没有——裸 {@code SimpleClientHttpRequestFactory}、
 * 跟随重定向、不过 {@link SsrfGuard}（安全审查 H5，2026-09-03）。安全代码复制必然漂移，
 * 故抽到这里让 Apache HttpClient 5 的三条路径共用；MCP 走 Reactor Netty，另有
 * {@code PinnedAddressResolverGroup} 承担同一职责。
 *
 * <p>两条不变量，改动时别退化：
 * <ul>
 *   <li>{@link #pinnedResolver} 只对校验期那个 host 返回校验期得到的地址，其它 host 一律
 *       {@link UnknownHostException}（fail-closed）——校验用的解析结果与实际连接用的必须是同一份，
 *       否则 TTL=0 的域名可在两次解析之间翻转成内网 IP；</li>
 *   <li>{@link #create} 必须 {@code disableRedirectHandling()}——重定向目标不再经 SsrfGuard 校验，
 *       且钉住的解析器只覆盖原 host，302 会把请求带去未经校验的主机。</li>
 * </ul>
 *
 * @author ruan
 */
public final class PinnedHttpClientFactory {

    private PinnedHttpClientFactory() {
    }

    /**
     * 连接期 DNS 钉住：只对 {@code host}（不区分大小写）返回 {@code pinned}，其它主机一律拒绝。
     */
    public static DnsResolver pinnedResolver(String host, InetAddress[] pinned) {
        return new SystemDefaultDnsResolver() {
            @Override
            public InetAddress[] resolve(String h) throws UnknownHostException {
                if (!host.equalsIgnoreCase(h)) {
                    throw new UnknownHostException("非预期主机（SSRF 钉住拒绝）: " + h);
                }
                return pinned;
            }
        };
    }

    /**
     * 按给定解析器与超时构造客户端；连接 / socket / 响应 / 取连接四段超时共用同一值。
     * 每次调用新建、用后即关（调用方 try-with-resources），故连接管理器不设上限。
     */
    public static CloseableHttpClient create(DnsResolver dnsResolver, int timeoutMs) {
        Timeout timeout = Timeout.ofMilliseconds(timeoutMs);
        // 连接超时在 httpclient5 5.x 已从 RequestConfig 迁至连接管理器的 ConnectionConfig。
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(timeout)
                .setSocketTimeout(timeout)
                .build();
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(dnsResolver)
                .setDefaultConnectionConfig(connectionConfig)
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(timeout)
                .setConnectionRequestTimeout(timeout)
                .build();
        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableRedirectHandling()
                .build();
    }
}
