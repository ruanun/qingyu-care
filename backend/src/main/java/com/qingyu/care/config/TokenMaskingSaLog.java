package com.qingyu.care.config;

import cn.dev33.satoken.log.SaLog;
import cn.dev33.satoken.util.StrFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * Sa-Token 的日志实现：脱掉会话凭证后交给 SLF4J。
 *
 * <p><b>为什么需要它</b>（安全审查 L2，2026-09-04 实测确认）：{@code sa-token.is-log: true} 时，
 * 内置的 {@code SaLogForConsole} 会把<b>完整 token</b> 打进 INFO：
 * <pre>SA [INFO] --&gt;: 账号 23 登录成功 (loginType=login), 会话凭证 token=257a91de-6b79-...</pre>
 * 拿真实 token 去历史日志里搜确实命中。token 有效期 7 天，任何能读日志的人都能直接拿它登录，
 * 日志收集系统、备份、误发的排查截图都是泄露面。
 *
 * <p>登录可观测性本身要保留（谁在什么时候登录/注销、配置有没有加载对），所以不是关掉日志，
 * 而是只脱掉凭证。顺带把这些日志从 {@code System.out} 接进 SLF4J，可以按 logger 名控制级别、
 * 随其它日志一起进文件与滚动。
 *
 * <p>⚠️ 脱敏正则用了 {@code \b} 词边界：配置日志里的 {@code tokenName=satoken} 不能被误伤，
 * 那条正是排查「请求头名字配错」时要看的。
 *
 * @author ruan
 */
public class TokenMaskingSaLog implements SaLog {

    private static final Logger log = LoggerFactory.getLogger("cn.dev33.satoken");

    /** 匹配 {@code token=<值>} / {@code Token = <值>}，值取到逗号、空白或右括号为止。 */
    private static final Pattern TOKEN_VALUE =
            Pattern.compile("(?i)(\\btoken\\s*=\\s*)([^,\\s\\]\\)]+)");

    /**
     * 渲染并脱敏。用 Sa-Token 自己的 {@link StrFormatter} 做占位符替换，避免猜它的占位符语法；
     * 渲染完再整体脱敏，比按参数位置猜哪个是 token 稳。包级 static 供单测直接断言。
     */
    static String render(String format, Object... args) {
        String message = StrFormatter.format(format, args);
        return TOKEN_VALUE.matcher(message).replaceAll("$1***");
    }

    @Override
    public void trace(String format, Object... args) {
        log.trace(render(format, args));
    }

    @Override
    public void debug(String format, Object... args) {
        log.debug(render(format, args));
    }

    @Override
    public void info(String format, Object... args) {
        log.info(render(format, args));
    }

    @Override
    public void warn(String format, Object... args) {
        log.warn(render(format, args));
    }

    @Override
    public void error(String format, Object... args) {
        log.error(render(format, args));
    }

    @Override
    public void fatal(String format, Object... args) {
        log.error(render(format, args));
    }
}
