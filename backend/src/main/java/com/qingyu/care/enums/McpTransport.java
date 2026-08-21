package com.qingyu.care.enums;

/**
 * MCP 远端传输方式。
 *
 * <p>刻意不含 stdio：它要求在平台服务器上 fork 子进程执行租户填写的命令行，
 * 等于给租户一个远程命令执行入口，多租户 SaaS 下没有安全的做法。
 *
 * @author ruan
 */
public enum McpTransport {
    /** 现行推荐传输 */
    STREAMABLE_HTTP,
    /** 兼容仅支持旧 SSE 传输的 server */
    SSE
}
