package com.qingyu.care.service.mcp;

import io.netty.resolver.AddressResolver;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 直接驱动 {@link PinnedAddressResolverGroup#newResolver}，绕开 {@link McpConnectionFactory}
 * 与真实网络，纯 JUnit 验证钉 IP 与 fail-closed 两条分支都真的被执行过——不用 Spring、不用
 * Docker、不用 mock。用 {@link ImmediateEventExecutor#INSTANCE} 让解析同步完成，不必等待。
 *
 * <p>{@code newResolver} 是 protected 方法，本类与被测类同包故可直接调用，且绕开了
 * {@code AddressResolverGroup.getResolver()} 的内部缓存与 {@code executor.terminationFuture()}
 * 监听器注册，测试本身不产生任何需要清理的资源。
 *
 * @author ruan
 */
class PinnedAddressResolverGroupTest {

    @Test
    @DisplayName("命中钉住的 host：返回校验期解析出的地址，不再走真实 DNS")
    void resolve_pinnedHost_returnsPinnedAddress() throws Exception {
        InetAddress pinnedAddress = InetAddress.getByName("93.184.216.34");
        PinnedAddressResolverGroup group =
                new PinnedAddressResolverGroup("example.com", new InetAddress[]{pinnedAddress});
        AddressResolver<InetSocketAddress> resolver = group.newResolver(ImmediateEventExecutor.INSTANCE);

        Future<InetSocketAddress> future = resolver.resolve(InetSocketAddress.createUnresolved("example.com", 443));
        assertThat(future.awaitUninterruptibly(2, TimeUnit.SECONDS)).isTrue();

        assertThat(future.isSuccess()).isTrue();
        assertThat(future.getNow().getAddress()).isEqualTo(pinnedAddress);
    }

    @Test
    @DisplayName("非钉住的 host：fail-closed 拒绝，不回退真实 DNS")
    void resolve_otherHost_failsClosed() throws Exception {
        InetAddress pinnedAddress = InetAddress.getByName("93.184.216.34");
        PinnedAddressResolverGroup group =
                new PinnedAddressResolverGroup("example.com", new InetAddress[]{pinnedAddress});
        AddressResolver<InetSocketAddress> resolver = group.newResolver(ImmediateEventExecutor.INSTANCE);

        Future<InetSocketAddress> future =
                resolver.resolve(InetSocketAddress.createUnresolved("evil.example.net", 443));
        assertThat(future.awaitUninterruptibly(2, TimeUnit.SECONDS)).isTrue();

        assertThat(future.isSuccess()).isFalse();
        assertThat(future.cause()).isInstanceOf(UnknownHostException.class);
    }
}
