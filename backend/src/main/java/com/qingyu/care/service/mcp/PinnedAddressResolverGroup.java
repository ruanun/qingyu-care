package com.qingyu.care.service.mcp;

import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.InetSocketAddressResolver;
import io.netty.resolver.SimpleNameResolver;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

/**
 * 只对指定 host 返回「已经过 SsrfGuard 校验」的地址，其它 host 一律 fail-closed 拒绝。
 *
 * <p>作用与 {@code RestClientHttpToolExecutor} 里那个自定义 DnsResolver 完全一致：让校验用的
 * 解析结果 == 实际连接用的地址，两次解析之间无法翻转 IP（DNS rebinding / TOCTOU）。
 * 主机名在 URL 中原样保留，故 TLS 的 SNI 与证书按主机名校验，不破坏合法 HTTPS。
 *
 * @author ruan
 */
class PinnedAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {

    private final String allowedHost;
    private final List<InetAddress> pinned;

    PinnedAddressResolverGroup(String allowedHost, InetAddress[] pinned) {
        this.allowedHost = allowedHost;
        this.pinned = Arrays.asList(pinned);
    }

    @Override
    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
        SimpleNameResolver<InetAddress> nameResolver = new SimpleNameResolver<>(executor) {
            @Override
            protected void doResolve(String inetHost, Promise<InetAddress> promise) {
                if (!allowedHost.equalsIgnoreCase(inetHost)) {
                    promise.setFailure(new UnknownHostException("非预期主机（SSRF 钉住拒绝）: " + inetHost));
                    return;
                }
                promise.setSuccess(pinned.get(0));
            }

            @Override
            protected void doResolveAll(String inetHost, Promise<List<InetAddress>> promise) {
                if (!allowedHost.equalsIgnoreCase(inetHost)) {
                    promise.setFailure(new UnknownHostException("非预期主机（SSRF 钉住拒绝）: " + inetHost));
                    return;
                }
                promise.setSuccess(pinned);
            }
        };
        return new InetSocketAddressResolver(executor, nameResolver);
    }
}
