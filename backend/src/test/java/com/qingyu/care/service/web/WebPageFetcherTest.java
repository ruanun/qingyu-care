package com.qingyu.care.service.web;

import com.qingyu.care.exception.BusinessException;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 单元测试：网页正文提取的噪声剔除、标题回退、内容哈希、HTTP 状态码校验、
 * Content-Type 白名单与 5MB 读取上限。
 * 这些都是静态纯函数，不发网络请求，可直接调用（同 ChatServiceImpl#meterContentStream 惯例）。
 *
 * <p>真正走一遍 {@code download()} 的那几条（重定向不跟随 / 编码自适应 / 门槛顺序）在
 * {@link WebPageFetcherDownloadTest}——它们需要一个本地 HTTP 监听器。
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
        String content = WebPageFetcher.extractFrom(Jsoup.parse(HTML, "https://example.com/policy"));

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
        assertThat(WebPageFetcher.extractTitle(Jsoup.parse(HTML), "https://example.com/policy"))
                .isEqualTo("退货政策 - 示例商城");
    }

    @Test
    void 无title时回退取首个h1() {
        String html = "<html><body><h1>帮助中心</h1><p>正文</p></body></html>";
        assertThat(WebPageFetcher.extractTitle(Jsoup.parse(html), "https://example.com/help"))
                .isEqualTo("帮助中心");
    }

    @Test
    void title与h1都无时回退用URL() {
        String html = "<html><body><p>只有正文</p></body></html>";
        assertThat(WebPageFetcher.extractTitle(Jsoup.parse(html), "https://example.com/x"))
                .isEqualTo("https://example.com/x");
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
        assertThat(WebPageFetcher.extractFrom(Jsoup.parse("<html></html>", "https://example.com"))).isEmpty();
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

    // ===== Content-Type 白名单（设计文档 §8 必测项）=====

    @Test
    void HTML与纯文本放行且忽略charset参数() {
        assertThatCode(() -> WebPageFetcher.requireAllowedContentType(MediaType.TEXT_HTML))
                .doesNotThrowAnyException();
        assertThatCode(() -> WebPageFetcher.requireAllowedContentType(MediaType.TEXT_PLAIN))
                .doesNotThrowAnyException();
        assertThatCode(() -> WebPageFetcher.requireAllowedContentType(
                MediaType.parseMediaType("text/html;charset=GBK"))).doesNotThrowAnyException();
    }

    @Test
    void 非白名单ContentType被拒() {
        // PDF/图片/二进制既解析不出正文，又会白读满 5MB 上限
        assertThatThrownBy(() -> WebPageFetcher.requireAllowedContentType(MediaType.APPLICATION_PDF))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的内容类型");
        assertThatThrownBy(() -> WebPageFetcher.requireAllowedContentType(MediaType.IMAGE_PNG))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的内容类型");
        assertThatThrownBy(() -> WebPageFetcher.requireAllowedContentType(MediaType.APPLICATION_OCTET_STREAM))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 缺失ContentType被拒() {
        // fail-closed：没声明类型就不猜，否则等于白名单形同虚设
        assertThatThrownBy(() -> WebPageFetcher.requireAllowedContentType(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的内容类型");
    }

    // ===== 5MB 读取上限（设计文档 §8 必测项）=====

    @Test
    void 响应体不超过5MB时完整读回() throws Exception {
        byte[] body = new byte[5 * 1024 * 1024];   // 恰好等于上限，不该被拒（判据是"超过"）
        assertThat(WebPageFetcher.readLimited(new ByteArrayInputStream(body))).hasSize(body.length);
    }

    @Test
    void 响应体超过5MB被拒() {
        byte[] body = new byte[5 * 1024 * 1024 + 1];
        assertThatThrownBy(() -> WebPageFetcher.readLimited(new ByteArrayInputStream(body)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("5MB");
    }

    // ===== 编码自适应（M-4：乱码的 hash 也是稳定的，会长期服务错误内容且永不自愈）=====

    @Test
    void 响应头无charset时按meta标签自适应GBK() {
        String html = "<html><head><meta charset=\"gbk\"><title>退货政策</title></head>"
                + "<body><p>七天无理由退货</p></body></html>";
        byte[] gbk = html.getBytes(Charset.forName("GBK"));

        org.jsoup.nodes.Document doc = WebPageFetcher.parseHtml(gbk, null, "https://example.com/policy");

        assertThat(WebPageFetcher.extractTitle(doc, "fallback")).isEqualTo("退货政策");
        assertThat(WebPageFetcher.extractFrom(doc)).contains("七天无理由退货");
    }

    @Test
    void 响应头声明的charset优先于meta标签() {
        // meta 写 gbk 但响应头说 UTF-8，且正文确实是 UTF-8 编码——以响应头为准才不乱码
        String html = "<html><head><meta charset=\"gbk\"><title>帮助中心</title></head>"
                + "<body><p>联系客服</p></body></html>";
        byte[] utf8 = html.getBytes(StandardCharsets.UTF_8);

        org.jsoup.nodes.Document doc = WebPageFetcher.parseHtml(utf8, "UTF-8", "https://example.com/help");

        assertThat(WebPageFetcher.extractTitle(doc, "fallback")).isEqualTo("帮助中心");
        assertThat(WebPageFetcher.extractFrom(doc)).contains("联系客服");
    }
}
