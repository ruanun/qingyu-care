package com.qingyu.care.service.mcp;

import io.modelcontextprotocol.client.McpSyncClient;

/**
 * {@link McpConnectionFactory#create} 的返回值：把 {@link McpSyncClient} 与它绑定的
 * {@code PinnedAddressResolverGroup} 打包，{@link #close()} 保证两者都被释放。
 *
 * <p>缺一不可：{@code PinnedAddressResolverGroup} 通过 {@code AddressResolverGroup.getResolver()}
 * 挂在 {@code HttpClient} 用的共享全局 event-loop 上时，会在其 {@code executor.terminationFuture()}
 * 上注册监听器，而该 executor 永不终止；只有显式调用 group 自己的 {@code close()} 才能摘掉这个
 * 监听器与内部缓存的 resolver。{@code McpSyncClient.closeGracefully()} 只关 transport，不知道也
 * 关不到这个 group——两者必须都关，任一遗漏都会让对应资源永久泄漏（group 泄漏是「每次
 * {@code create()} 一个 group + 每个碰过它的 event-loop 线程一个 resolver」，长期运行下是缓慢的
 * 内存/线程泄漏）。
 *
 * @author ruan
 */
public final class McpConnection {

    private final McpSyncClient client;
    private final PinnedAddressResolverGroup resolverGroup;

    McpConnection(McpSyncClient client, PinnedAddressResolverGroup resolverGroup) {
        this.client = client;
        this.resolverGroup = resolverGroup;
    }

    /** 已初始化的 MCP 客户端，调用方用它发起工具调用/读取能力。 */
    public McpSyncClient client() {
        return client;
    }

    /**
     * 同时关闭 MCP 客户端与钉住的 resolver group。任一失败都不得吞掉另一个的关闭，
     * 否则某一次异常会让另一半资源永久泄漏。
     */
    public void close() {
        RuntimeException failure = null;
        try {
            client.closeGracefully();
        } catch (RuntimeException e) {
            failure = e;
        }
        try {
            resolverGroup.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
