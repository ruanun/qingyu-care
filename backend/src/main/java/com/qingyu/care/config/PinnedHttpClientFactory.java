package com.qingyu.care.config;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
 *   <li>出站调用必须包在 {@link #callWithDeadline} 里——{@link #create} 设的超时全是<b>单次读的空闲超时</b>，
 *       对端先回 200 再每隔几秒吐 1 字节，这次调用就永远不结束（2026-09-30 安全审查 H3）。</li>
 * </ul>
 *
 * @author ruan
 */
public final class PinnedHttpClientFactory {

    /** 截止看门狗：只做「到点关客户端」这一件事，单线程足够，daemon 不拦 JVM 退出。 */
    private static final ScheduledExecutorService DEADLINE_WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "outbound-deadline");
                t.setDaemon(true);
                return t;
            });

    private PinnedHttpClientFactory() {
    }

    /** 出站调用体：允许抛 {@link IOException}，与 Spring 的 exchange / RestClient 调用对齐。 */
    @FunctionalInterface
    public interface OutboundCall<T> {
        T call() throws IOException;
    }

    /** 出站调用超过总时长、被看门狗强制中断。 */
    public static class DeadlineExceededException extends IllegalStateException {
        public DeadlineExceededException(long deadlineMs) {
            super("出站请求超过总时长上限 " + deadlineMs + "ms，已强制中断");
        }
    }

    /**
     * 给一次出站调用加<b>墙钟总时长</b>上限：到点由看门狗 {@code close(CloseMode.IMMEDIATE)} 掉整个客户端，
     * 连同正在读的连接一起关闭，阻塞中的读取随即抛异常。
     *
     * <p>为什么非要这个：{@link #create} 的连接 / socket / 响应超时都是「两次读之间的间隔」，不是总时长。
     * 对端先回状态行、再慢速滴灌 body（或无限快速吐字节），调用就永远不结束；而 Spring 关闭响应时还会
     * 把剩余 body 读完（{@code EntityUtils.consume}），连「只取状态码」的 Webhook 也躲不掉。
     * 2026-09-30 安全审查 H3：8 个这样的回调地址就能占满 Boot 默认异步池，全平台审计 / 计量 / Webhook 一起停摆。
     *
     * <p>{@code client} 必须是这次调用专用、用后即关的那个（本类 {@link #create} 的用法），看门狗会把它整个关掉。
     */
    public static <T> T callWithDeadline(CloseableHttpClient client, long deadlineMs, OutboundCall<T> call)
            throws IOException {
        AtomicBoolean fired = new AtomicBoolean(false);
        ScheduledFuture<?> watchdog = DEADLINE_WATCHDOG.schedule(() -> {
            fired.set(true);
            client.close(CloseMode.IMMEDIATE);
        }, deadlineMs, TimeUnit.MILLISECONDS);
        try {
            T result = call.call();
            // 被看门狗关掉时 Spring 关闭响应的那步 IOException 会被它自己吞掉，调用看似正常返回——
            // 只要截止触发过就一律按失败算，别把「超时被掐断」当成功
            if (fired.get()) {
                throw new DeadlineExceededException(deadlineMs);
            }
            return result;
        } catch (IOException | RuntimeException e) {
            if (e instanceof DeadlineExceededException) {
                throw e;
            }
            if (fired.get()) {
                throw new DeadlineExceededException(deadlineMs);
            }
            throw e;
        } finally {
            watchdog.cancel(false);
        }
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
