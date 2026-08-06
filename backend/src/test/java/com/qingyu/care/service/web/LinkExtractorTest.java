package com.qingyu.care.service.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jsoup.Jsoup;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LinkExtractor} 单测。纯函数、不碰网络与数据库。
 *
 * @author ruan
 */
class LinkExtractorTest {

    @Nested
    @DisplayName("normalize")
    class Normalize {

        @Test
        @DisplayName("去掉 fragment——锚点是同一页")
        void strips_fragment() {
            assertThat(LinkExtractor.normalize("https://x.com/help/#top")).isEqualTo("https://x.com/help");
        }

        @Test
        @DisplayName("去掉 tracking 参数，保留其余参数并按名排序")
        void strips_tracking_params_and_sorts_rest() {
            assertThat(LinkExtractor.normalize("https://x.com/help?page=2&utm_source=a&fbclid=b"))
                    .isEqualTo("https://x.com/help?page=2");
            assertThat(LinkExtractor.normalize("https://x.com/help?b=2&a=1"))
                    .isEqualTo("https://x.com/help?a=1&b=2");
        }

        @Test
        @DisplayName("路径末尾斜杠统一去掉，但根路径保留")
        void unifies_trailing_slash_but_keeps_root() {
            assertThat(LinkExtractor.normalize("https://x.com/help/")).isEqualTo("https://x.com/help");
            assertThat(LinkExtractor.normalize("https://x.com/help")).isEqualTo("https://x.com/help");
            assertThat(LinkExtractor.normalize("https://x.com/help///")).isEqualTo("https://x.com/help");
            assertThat(LinkExtractor.normalize("https://x.com/")).isEqualTo("https://x.com/");
            assertThat(LinkExtractor.normalize("https://x.com")).isEqualTo("https://x.com/");
        }

        @Test
        @DisplayName("host 小写，scheme 不升级")
        void lowercases_host_without_upgrading_scheme() {
            assertThat(LinkExtractor.normalize("HTTP://X.COM/Help")).isEqualTo("http://x.com/Help");
        }

        @Test
        @DisplayName("非 http(s) 与非法 URL 返回 null")
        void rejects_non_http_and_malformed() {
            assertThat(LinkExtractor.normalize("mailto:a@b.com")).isNull();
            assertThat(LinkExtractor.normalize("javascript:alert(1)")).isNull();
            assertThat(LinkExtractor.normalize("tel:123")).isNull();
            assertThat(LinkExtractor.normalize("ht tp://x.com")).isNull();
            assertThat(LinkExtractor.normalize("https://")).isNull();
        }

        @Test
        @DisplayName("非默认端口保留")
        void keeps_explicit_port() {
            assertThat(LinkExtractor.normalize("https://x.com:8443/help/")).isEqualTo("https://x.com:8443/help");
        }
    }

    @Nested
    @DisplayName("inScope 的三个边界")
    class InScope {

        @Test
        @DisplayName("边界一：入口页自身在范围内（规范化去掉了斜杠，前缀带斜杠）")
        void entry_page_itself_is_in_scope() {
            assertThat(LinkExtractor.inScope("https://x.com/help", "https://x.com/help")).isTrue();
        }

        @Test
        @DisplayName("边界二：helpdesk 这类兄弟目录被排除")
        void sibling_directory_sharing_prefix_is_excluded() {
            assertThat(LinkExtractor.inScope("https://x.com/helpdesk", "https://x.com/help")).isFalse();
            assertThat(LinkExtractor.inScope("https://x.com/help-center", "https://x.com/help")).isFalse();
        }

        @Test
        @DisplayName("边界三：根路径前缀能匹配子页")
        void root_prefix_matches_children() {
            assertThat(LinkExtractor.inScope("https://x.com/faq", "https://x.com/")).isTrue();
            assertThat(LinkExtractor.inScope("https://x.com/", "https://x.com/")).isTrue();
        }

        @Test
        @DisplayName("范围内的子页与更深层都放行")
        void children_and_grandchildren_are_in_scope() {
            assertThat(LinkExtractor.inScope("https://x.com/help/faq", "https://x.com/help")).isTrue();
            assertThat(LinkExtractor.inScope("https://x.com/help/faq/a", "https://x.com/help")).isTrue();
        }
    }

    @Nested
    @DisplayName("extract")
    class Extract {

        private org.jsoup.nodes.Document dom(String bodyHtml) {
            return Jsoup.parse("<html><body>" + bodyHtml + "</body></html>", "https://x.com/help/");
        }

        @Test
        @DisplayName("相对地址按 baseUri 解析")
        void resolves_relative_urls() {
            Set<String> links = LinkExtractor.extract(dom("<a href='faq'>f</a>"), "https://x.com/help");
            assertThat(links).containsExactly("https://x.com/help/faq");
        }

        @Test
        @DisplayName("外域链接被排除")
        void excludes_other_hosts() {
            Set<String> links = LinkExtractor.extract(
                    dom("<a href='https://other.com/help/x'>o</a><a href='/help/ok'>k</a>"),
                    "https://x.com/help");
            assertThat(links).containsExactly("https://x.com/help/ok");
        }

        @Test
        @DisplayName("范围外的同域链接被排除")
        void excludes_out_of_scope_same_host() {
            Set<String> links = LinkExtractor.extract(
                    dom("<a href='/blog/post'>b</a><a href='/help/ok'>k</a>"),
                    "https://x.com/help");
            assertThat(links).containsExactly("https://x.com/help/ok");
        }

        @Test
        @DisplayName("非 http(s) 协议与空 href 被排除，不抛异常")
        void excludes_non_http_schemes() {
            Set<String> links = LinkExtractor.extract(
                    dom("<a href='mailto:a@b.com'>m</a><a href='javascript:void(0)'>j</a>"
                        + "<a href=''>e</a><a href='/help/ok'>k</a>"),
                    "https://x.com/help");
            assertThat(links).containsExactly("https://x.com/help/ok");
        }

        @Test
        @DisplayName("同一页的不同写法规范化后只保留一份")
        void dedupes_after_normalization() {
            Set<String> links = LinkExtractor.extract(
                    dom("<a href='/help/faq'>1</a><a href='/help/faq/'>2</a>"
                        + "<a href='/help/faq#top'>3</a><a href='/help/faq?utm_source=x'>4</a>"),
                    "https://x.com/help");
            assertThat(links).containsExactly("https://x.com/help/faq");
        }
    }
}
