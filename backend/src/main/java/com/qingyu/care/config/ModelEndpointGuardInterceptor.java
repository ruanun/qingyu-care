package com.qingyu.care.config;

import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Response;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 租户模型 endpoint 的出站防线：<b>禁重定向 + 连接期 DNS 校验 + 实连 IP 复核</b>（2026-09-30 安全审查 M1）。
 *
 * <p>为什么要这个类：模型调用走 Spring AI 2.0 → openai-java → OkHttp，完全不经 {@link PinnedHttpClientFactory}。
 * OkHttp 默认跟随重定向（含 https→http），而 {@code ModelRouterService.requireValidEndpoint} 只在落库时校验一次——
 * 租户把 endpoint 填成自己的公网服务器，让它回 {@code 307 Location: http://169.254.169.254/...}，平台就会带着
 * 请求体去打云元数据 / 内网；「测试连接」还会把上游错误原文回显出来。TTL=0 的域名做 DNS rebinding 同理。
 *
 * <p>为什么是「短路」写法：Spring AI 的 {@code SpringAiOpenAiHttpClient.Builder} 不暴露 {@code followRedirects}
 * 与 {@code dns}，只给一个 {@code interceptor()}，而它挂的是<b>应用拦截器</b>——位于 OkHttp 重定向循环的外层，
 * 调 {@code chain.proceed} 就等于放任它跟随。所以这里不 proceed，而是用自己这个关了重定向、带守卫 DNS 的
 * {@link OkHttpClient} 发出同一个请求，超时从外层链原样继承。返回的 3xx 直接判失败，不交给上层。
 *
 * <p>{@code allowPrivate}（{@code qingyu.care.model.allow-private-endpoints}，仅 dev 打开）放行环回与私网，
 * 但云元数据段任何时候都拦——与安全基线第 26 条一致。
 *
 * @author ruan
 */
public final class ModelEndpointGuardInterceptor implements Interceptor {

    /** 守卫拒绝：消息里只有主机名 / IP，可安全回显给管理员。 */
    public static class EndpointRejectedException extends IOException {
        public EndpointRejectedException(String message) {
            super(message);
        }
    }

    private final OkHttpClient guarded;

    public ModelEndpointGuardInterceptor(boolean allowPrivate) {
        this.guarded = new OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                // 不走系统代理：经代理时 DNS 由代理解析，下面的守卫就形同虚设
                .proxy(Proxy.NO_PROXY)
                .retryOnConnectionFailure(false)
                .dns(host -> List.of(resolve(host, allowPrivate)))
                // 网络拦截器能拿到实际连上的地址：IP 字面量不经 Dns，靠这里兜住
                .addNetworkInterceptor(chain -> {
                    Connection conn = chain.connection();
                    if (conn != null) {
                        check(conn.socket().getInetAddress(), allowPrivate, chain.request().url().host());
                    }
                    return chain.proceed(chain.request());
                })
                .build();
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        OkHttpClient client = guarded.newBuilder()
                .connectTimeout(chain.connectTimeoutMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(chain.readTimeoutMillis(), TimeUnit.MILLISECONDS)
                .writeTimeout(chain.writeTimeoutMillis(), TimeUnit.MILLISECONDS)
                .build();
        Call call = client.newCall(chain.request());
        call.timeout().timeout(chain.call().timeout().timeoutNanos(), TimeUnit.NANOSECONDS);
        Response response = call.execute();
        if (response.isRedirect()) {
            int code = response.code();
            response.close();
            throw new EndpointRejectedException("模型端点返回重定向（HTTP " + code + "），已拒绝跟随");
        }
        return response;
    }

    private static InetAddress[] resolve(String host, boolean allowPrivate) throws UnknownHostException {
        InetAddress[] addrs = InetAddress.getAllByName(host);
        for (InetAddress a : addrs) {
            check(a, allowPrivate, host);
        }
        return addrs;
    }

    private static void check(InetAddress a, boolean allowPrivate, String host) throws UnknownHostException {
        if (SsrfGuard.isCloudMetadata(a) || (!allowPrivate && SsrfGuard.isBlocked(a))) {
            // OkHttp 的 Dns 契约只允许抛 UnknownHostException；消息与 SsrfGuard 口径一致，只含主机名
            throw new UnknownHostException("模型端点指向内网或元数据地址，已拒绝: " + host);
        }
    }
}
