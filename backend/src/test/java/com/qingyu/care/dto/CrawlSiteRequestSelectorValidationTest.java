package com.qingyu.care.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 站点爬取的正文选择器落库校验（2026-09-30 安全审查 M2）：拒绝 jsoup 的正则类选择器，
 * 普通 CSS 选择器照常放行。执行侧的兜底见 {@code WebPageFetcherSelectorTest}。
 *
 * @author ruan
 */
class CrawlSiteRequestSelectorValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private boolean selectorValid(String selector) {
        CrawlSiteRequest req = new CrawlSiteRequest();
        req.setContentSelector(selector);
        return validator.validateProperty(req, "contentSelectorSafe").isEmpty();
    }

    @Test
    void 拒绝正则类选择器() {
        assertThat(selectorValid("div:matches((a+)+$)")).isFalse();
        assertThat(selectorValid("p:MATCHESOWN(x)")).isFalse();
        assertThat(selectorValid("div:matchesWholeText(.*)")).isFalse();
        assertThat(selectorValid("a[href~=^/help]")).isFalse();
        assertThat(selectorValid(".doc\n:matches(x)")).isFalse();
    }

    @Test
    void 普通选择器放行() {
        assertThat(selectorValid(".doc-content")).isTrue();
        assertThat(selectorValid("article > div.body, main [data-role=content]")).isTrue();
        assertThat(selectorValid("div:contains(退货)")).isTrue();
        assertThat(selectorValid(null)).isTrue();
    }
}
