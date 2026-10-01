package com.qingyu.care.util;

import java.util.Locale;

/**
 * jsoup 选择器的安全判定，落库校验（{@code CrawlSiteRequest}）与执行侧兜底（{@code WebPageFetcher}）共用这一份。
 *
 * @author ruan
 */
public final class JsoupSelectors {

    private JsoupSelectors() {
    }

    /**
     * 是否含会编译用户正则的选择器：{@code :matches} / {@code :matchesOwn} / {@code :matchesWholeText} 等与
     * {@code [attr~=regex]}。jsoup 用 JDK 正则、没有超时，{@code :matches((.*a){20}$)} 能把线程卡死（2026-09-30 安全审查 M2）。
     */
    public static boolean usesRegex(String selector) {
        String s = selector.toLowerCase(Locale.ROOT);
        return s.contains(":matches") || s.contains("~=");
    }
}
