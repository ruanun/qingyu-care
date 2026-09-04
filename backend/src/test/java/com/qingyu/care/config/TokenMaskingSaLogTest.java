package com.qingyu.care.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sa-Token 的日志脱敏（安全审查 L2，2026-09-04）。
 *
 * <p>实测证据：{@code is-log: true} 时 Sa-Token 会把<b>完整会话凭证</b>打进 INFO：
 * <pre>SA [INFO] --&gt;: 账号 23 登录成功 (loginType=login), 会话凭证 token=257a91de-6b79-...</pre>
 * 拿真实 token 去历史日志里搜，确实命中多次。任何能读日志的人都能直接拿它登录，有效期 7 天。
 *
 * <p>登录可观测性本身是要保留的，所以不是关掉日志，而是把 token 脱掉：复用 Sa-Token 自己的
 * {@code StrFormatter} 渲染消息，脱敏后交给 SLF4J（顺带让这些日志进 logback、可按级别与文件管理，
 * 原实现是直接 {@code System.out}）。
 *
 * @author ruan
 */
class TokenMaskingSaLogTest {

    @Test
    void 会话凭证被脱敏() {
        String out = TokenMaskingSaLog.render(
                "账号 {} 登录成功 (loginType={}), 会话凭证 token={}", 23, "login",
                "257a91de-6b79-4838-8ee8-13a06b70a35b");

        assertThat(out).contains("账号 23 登录成功").contains("loginType=login");
        assertThat(out).contains("token=***");
        assertThat(out).doesNotContain("257a91de");
    }

    @Test
    void 注销日志同样脱敏() {
        String out = TokenMaskingSaLog.render(
                "账号 {} 注销登录 (loginType={}), 会话凭证 token={}", 7, "login", "abc-def-123");

        assertThat(out).contains("token=***").doesNotContain("abc-def-123");
    }

    /**
     * 变异实验：把正则的 {@code \\b} 去掉，本条转红——配置日志里的 {@code tokenName=satoken}
     * 会被当成凭证脱掉，那条日志正是排查「请求头名字配错」时要看的。
     */
    @Test
    void 配置里的tokenName不被误伤() {
        String out = TokenMaskingSaLog.render(
                "全局配置 SaTokenConfig [tokenName={}, timeout={}, isShare={}]", "satoken", 604800, false);

        assertThat(out).contains("tokenName=satoken");
        assertThat(out).doesNotContain("***");
    }

    @Test
    void 无凭据的消息原样输出() {
        String out = TokenMaskingSaLog.render("SaSession [satoken:login:session:{}] 创建成功", 23);

        assertThat(out).isEqualTo("SaSession [satoken:login:session:23] 创建成功");
    }

    /** 大小写与空格的变体也要覆盖，否则换个措辞就漏了。 */
    @Test
    void 大小写与空格变体都被脱敏() {
        assertThat(TokenMaskingSaLog.render("Token = {}", "aaa-bbb")).contains("***").doesNotContain("aaa-bbb");
        assertThat(TokenMaskingSaLog.render("token={}", "ccc-ddd")).contains("***").doesNotContain("ccc-ddd");
    }
}
