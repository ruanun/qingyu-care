package com.qingyu.care.service.web;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WebPageFetcher} 的选择器分支与「剔噪前 DOM 副本」单测。
 * 全部打在包级 static 方法上，不发网络请求。
 *
 * @author ruan
 */
class WebPageFetcherSelectorTest {

    private static final String PAGE = """
            <html><head><title>帮助中心</title></head><body>
              <nav><a href="/help/faq">常见问题</a><a href="/help/contact">联系我们</a>导航噪声</nav>
              <div class="doc-content"><p>这是正文内容。</p></div>
              <footer>页脚版权噪声</footer>
            </body></html>
            """;

    @Test
    @DisplayName("配了选择器：只取容器内文本，导航与页脚都不在")
    void selector_takes_only_container_text() {
        org.jsoup.nodes.Document doc = Jsoup.parse(PAGE, "https://x.com/help/");

        String content = WebPageFetcher.extractBySelector(doc, ".doc-content");

        assertThat(content).contains("这是正文内容");
        assertThat(content).doesNotContain("导航噪声").doesNotContain("页脚版权噪声");
    }

    @Test
    @DisplayName("选择器选不中任何元素：返回空串，由调用方回退启发式")
    void selector_matching_nothing_returns_blank() {
        org.jsoup.nodes.Document doc = Jsoup.parse(PAGE, "https://x.com/help/");

        assertThat(WebPageFetcher.extractBySelector(doc, ".not-exist")).isBlank();
    }

    @Test
    @DisplayName("选择器语法非法：返回空串而不是抛异常——写错选择器不该让整站抓取全线失败")
    void invalid_selector_returns_blank_instead_of_throwing() {
        org.jsoup.nodes.Document doc = Jsoup.parse(PAGE, "https://x.com/help/");

        assertThat(WebPageFetcher.extractBySelector(doc, "div[")).isBlank();
    }

    @Test
    @DisplayName("多个匹配元素取并集")
    void multiple_matches_are_joined() {
        org.jsoup.nodes.Document doc = Jsoup.parse(
                "<html><body><p class='c'>一</p><p class='c'>二</p></body></html>", "https://x.com/");

        String content = WebPageFetcher.extractBySelector(doc, ".c");

        assertThat(content).contains("一").contains("二");
    }

    @Test
    @DisplayName("剔噪会就地改树——这是 dom 必须 clone 的原因，此处固定住该前提")
    void extractFrom_mutates_the_tree_in_place() {
        org.jsoup.nodes.Document doc = Jsoup.parse(PAGE, "https://x.com/help/");
        assertThat(doc.select("nav a")).isNotEmpty();

        WebPageFetcher.extractFrom(doc);

        // nav 整个被移除，导航里的链接一起消失——若把这棵树交给 LinkExtractor，一条链接都提不到
        assertThat(doc.select("nav a")).isEmpty();
    }

    @Test
    @DisplayName("剔噪前 clone 的副本保留 nav 里的链接")
    void cloned_dom_keeps_nav_links_after_noise_removal() {
        org.jsoup.nodes.Document doc = Jsoup.parse(PAGE, "https://x.com/help/");
        org.jsoup.nodes.Document snapshot = doc.clone();

        WebPageFetcher.extractFrom(doc);

        assertThat(doc.select("nav a")).isEmpty();
        assertThat(snapshot.select("nav a")).hasSize(2);
        assertThat(snapshot.select("nav a").first().absUrl("href")).isEqualTo("https://x.com/help/faq");
    }

    /**
     * 端到端：直接打在 {@link WebPageFetcher#fetch(String, String)} 上，而不是各自独立的
     * Jsoup clone() 语义（上面那条测试）——上面那条永远不调用 {@code fetch()}，删掉 {@code fetch}
     * 方法体里的 {@code .clone()} 它也照样绿，抓不住真正的回归。
     *
     * <p>用匿名子类覆盖 {@code downloadRaw} 绕开真实网络与 {@code SsrfGuard}（loopback 会被拒），
     * 让 {@code fetch()} 其余的真实编排逻辑（parseHtml → clone → extractTitle → extractFrom）
     * 照常执行——这是 {@code downloadRaw} 提升为 public、且类与方法都非 final 才具备的测试手段。
     */
    @Test
    @DisplayName("端到端：fetch() 返回的 dom 是剔噪前的独立副本，即便 content 已被剔噪")
    void fetch_end_to_end_dom_survives_noise_removal() {
        WebPageFetcher fetcher = new WebPageFetcher() {
            @Override
            public Downloaded downloadRaw(String url) {
                return new Downloaded(PAGE.getBytes(StandardCharsets.UTF_8), "UTF-8", 200);
            }
        };

        WebPageFetcher.FetchResult result = fetcher.fetch("https://x.com/help/");

        assertThat(result.content()).doesNotContain("导航噪声");
        assertThat(result.dom().select("nav a")).hasSize(2);
    }
}
