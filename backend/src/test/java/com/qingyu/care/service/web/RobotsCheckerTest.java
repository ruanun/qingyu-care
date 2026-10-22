package com.qingyu.care.service.web;

import com.qingyu.care.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@link RobotsChecker} 单测。解析部分纯字符串、不发网络；抓取部分 mock
 * {@link WebPageFetcher#downloadRaw}。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class RobotsCheckerTest {

    @Mock
    private WebPageFetcher webPageFetcher;

    @Nested
    @DisplayName("parse")
    class Parse {

        @Test
        @DisplayName("收 User-agent:* 段的 Disallow，按路径前缀拦")
        void collects_disallow_of_star_section() {
            RobotsChecker.RobotsRules rules = RobotsChecker.parse("""
                    User-agent: *
                    Disallow: /admin
                    Disallow: /private/
                    """);

            assertThat(rules.allows("https://x.com/admin/users")).isFalse();
            assertThat(rules.allows("https://x.com/private/a")).isFalse();
            assertThat(rules.allows("https://x.com/help/faq")).isTrue();
        }

        @Test
        @DisplayName("只认 * 段：其它 UA 段的 Disallow 不生效")
        void ignores_other_user_agent_sections() {
            RobotsChecker.RobotsRules rules = RobotsChecker.parse("""
                    User-agent: Googlebot
                    Disallow: /help

                    User-agent: *
                    Disallow: /admin
                    """);

            assertThat(rules.allows("https://x.com/help/faq")).isTrue();
            assertThat(rules.allows("https://x.com/admin")).isFalse();
        }

        @Test
        @DisplayName("Disallow 空值表示允许全部，不能被当成前缀 \"\" 拦下一切")
        void empty_disallow_means_allow_all() {
            RobotsChecker.RobotsRules rules = RobotsChecker.parse("""
                    User-agent: *
                    Disallow:
                    """);

            assertThat(rules.disallowedPaths()).isEmpty();
            assertThat(rules.allows("https://x.com/anything")).isTrue();
        }

        @Test
        @DisplayName("解析 Crawl-delay，支持小数秒")
        void parses_crawl_delay_including_fractional() {
            assertThat(RobotsChecker.parse("User-agent: *\nCrawl-delay: 2").crawlDelayMs()).isEqualTo(2000L);
            assertThat(RobotsChecker.parse("User-agent: *\nCrawl-delay: 0.5").crawlDelayMs()).isEqualTo(500L);
            assertThat(RobotsChecker.parse("User-agent: *\nCrawl-delay: abc").crawlDelayMs()).isNull();
            assertThat(RobotsChecker.parse("User-agent: *\nDisallow: /a").crawlDelayMs()).isNull();
        }

        @Test
        @DisplayName("注释与空行被忽略，key 大小写不敏感")
        void ignores_comments_and_is_case_insensitive() {
            RobotsChecker.RobotsRules rules = RobotsChecker.parse("""
                    # 这是注释
                    USER-AGENT: *

                    DISALLOW: /admin   # 行尾注释
                    """);

            assertThat(rules.allows("https://x.com/admin")).isFalse();
        }

        @Test
        @DisplayName("空文件或全是无关内容 → 允许全部")
        void blank_body_allows_all() {
            assertThat(RobotsChecker.parse("").allows("https://x.com/a")).isTrue();
            assertThat(RobotsChecker.parse("Sitemap: https://x.com/sitemap.xml").allows("https://x.com/a")).isTrue();
        }

        @Test
        @DisplayName("allows 只比路径，查询串不影响判定")
        void allows_compares_path_only() {
            RobotsChecker.RobotsRules rules = RobotsChecker.parse("User-agent: *\nDisallow: /admin");

            assertThat(rules.allows("https://x.com/admin?page=2")).isFalse();
            assertThat(rules.allows("https://x.com/help?admin=1")).isTrue();
        }

        @Test
        @DisplayName("连续 User-agent 行属于同一组，规则对组内所有 UA 生效")
        void consecutive_user_agent_lines_share_group_rules() {
            RobotsChecker.RobotsRules rules = RobotsChecker.parse("""
                    User-agent: *
                    User-agent: Googlebot
                    Disallow: /shared
                    """);

            assertThat(rules.allows("https://x.com/shared/x")).isFalse();
            assertThat(rules.allows("https://x.com/other")).isTrue();
        }

        @Test
        @DisplayName("UTF-8 BOM 开头不影响首行 User-agent 识别")
        void strips_leading_bom() {
            RobotsChecker.RobotsRules rules = RobotsChecker.parse("﻿User-agent: *\nDisallow: /admin\n");

            assertThat(rules.allows("https://x.com/admin")).isFalse();
        }
    }

    @Nested
    @DisplayName("fetch")
    class Fetch {

        @Test
        @DisplayName("正常取到并解析")
        void fetches_and_parses() {
            byte[] body = "User-agent: *\nDisallow: /admin\n".getBytes(StandardCharsets.UTF_8);
            when(webPageFetcher.downloadRaw("https://x.com/robots.txt"))
                    .thenReturn(new WebPageFetcher.Downloaded(body, "UTF-8", 200));

            RobotsChecker.RobotsRules rules = RobotsChecker.fetch("https://x.com", webPageFetcher);

            assertThat(rules.allows("https://x.com/admin")).isFalse();
        }

        @Test
        @DisplayName("robots.txt 取不到（404/超时）按允许全部处理——多数站点没有这个文件")
        void missing_robots_allows_all() {
            when(webPageFetcher.downloadRaw(anyString()))
                    .thenThrow(new BusinessException("网页返回 HTTP 404"));

            RobotsChecker.RobotsRules rules = RobotsChecker.fetch("https://x.com", webPageFetcher);

            assertThat(rules.allows("https://x.com/anything")).isTrue();
            assertThat(rules.disallowedPaths()).isEmpty();
        }

        @Test
        @DisplayName("charsetName 为 null 时按 UTF-8 解码，不抛异常")
        void tolerates_null_charset() {
            byte[] body = "User-agent: *\nDisallow: /admin\n".getBytes(StandardCharsets.UTF_8);
            when(webPageFetcher.downloadRaw(anyString()))
                    .thenReturn(new WebPageFetcher.Downloaded(body, null, 200));

            assertThat(RobotsChecker.fetch("https://x.com", webPageFetcher)
                    .allows("https://x.com/admin")).isFalse();
        }
    }
}
