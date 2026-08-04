package com.qingyu.care.service.web;

import com.qingyu.care.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 单元测试：网页正文提取的噪声剔除、标题回退、内容哈希与 HTTP 状态码校验。
 * 这些都是静态纯函数，不发网络请求，可直接调用（同 ChatServiceImpl#meterContentStream 惯例）。
 *
 * @author ruan
 */
class WebPageFetcherTest {

    private static final String HTML = """
            <html>
              <head><title>退货政策 - 示例商城</title></head>
              <body>
                <nav>首页 商品 关于我们 联系我们</nav>
                <header>示例商城 购物车(0)</header>
                <aside>推荐商品 热销榜</aside>
                <script>var gtag = function(){};</script>
                <style>.hidden{display:none}</style>
                <main>
                  <h1>退货政策</h1>
                  <p>自购买之日起七天内可无理由退货。</p>
                </main>
                <footer>© 2026 示例商城 京ICP备12345号</footer>
              </body>
            </html>
            """;

    @Test
    void 剔除导航页脚侧边栏与脚本样式() {
        String content = WebPageFetcher.extractFrom(HTML, "https://example.com/policy");

        assertThat(content).contains("退货政策").contains("七天内可无理由退货");
        assertThat(content).doesNotContain("购物车");
        assertThat(content).doesNotContain("关于我们");
        assertThat(content).doesNotContain("热销榜");
        assertThat(content).doesNotContain("京ICP备");
        assertThat(content).doesNotContain("gtag");
        assertThat(content).doesNotContain("display:none");
    }

    @Test
    void 标题优先取title标签() {
        assertThat(WebPageFetcher.extractTitle(HTML, "https://example.com/policy"))
                .isEqualTo("退货政策 - 示例商城");
    }

    @Test
    void 无title时回退取首个h1() {
        String html = "<html><body><h1>帮助中心</h1><p>正文</p></body></html>";
        assertThat(WebPageFetcher.extractTitle(html, "https://example.com/help")).isEqualTo("帮助中心");
    }

    @Test
    void title与h1都无时回退用URL() {
        String html = "<html><body><p>只有正文</p></body></html>";
        assertThat(WebPageFetcher.extractTitle(html, "https://example.com/x")).isEqualTo("https://example.com/x");
    }

    @Test
    void 内容哈希对同一内容稳定对不同内容不同() {
        String a = WebPageFetcher.sha256("退货政策：七天无理由");
        String b = WebPageFetcher.sha256("退货政策：七天无理由");
        String c = WebPageFetcher.sha256("退货政策：十五天无理由");

        assertThat(a).isEqualTo(b).hasSize(64);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void 空body不抛异常返回空串() {
        assertThat(WebPageFetcher.extractFrom("<html></html>", "https://example.com")).isEmpty();
    }

    @Test
    void 状态码为2xx放行() {
        assertThatCode(() -> WebPageFetcher.requireSuccessStatus(200)).doesNotThrowAnyException();
        assertThatCode(() -> WebPageFetcher.requireSuccessStatus(201)).doesNotThrowAnyException();
        assertThatCode(() -> WebPageFetcher.requireSuccessStatus(204)).doesNotThrowAnyException();
    }

    @Test
    void 状态码非2xx抛异常且消息含状态码() {
        assertThatThrownBy(() -> WebPageFetcher.requireSuccessStatus(404))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("404");
        assertThatThrownBy(() -> WebPageFetcher.requireSuccessStatus(500))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("500");
        assertThatThrownBy(() -> WebPageFetcher.requireSuccessStatus(301))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("301");
    }
}
