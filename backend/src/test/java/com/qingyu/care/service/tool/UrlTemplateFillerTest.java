package com.qingyu.care.service.tool;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * URL 模板填充的边界。
 *
 * <p>本类守的是一个**既有缺陷的修复**：旧实现遍历 args 逐个 replace，LLM 没填的占位符会
 * 原样留在 URL 里被真的发出去（{@code ?limit={limit}}），可选 query 参数因此在本项目里
 * 一直是坏的——手工配置也一样坏，只是没人系统性地用过。
 *
 * <p>⚠️ 变异验证（实施时必须真跑一次）：把 {@code fill} 里「段内仍有占位符则跳过该段」
 * 改回「保留该段」，{@code 可选query参数未填时整段删除} 必须转红。
 *
 * @author ruan
 */
class UrlTemplateFillerTest {

    // ===== path 段 =====

    @Test
    void path占位符被URL编码后替换() {
        String url = UrlTemplateFiller.fill(
                "https://api.example.com/orders/{orderId}", Map.of("orderId", "A B&C"));

        assertThat(url).isEqualTo("https://api.example.com/orders/A+B%26C");
    }

    @Test
    void path占位符未填时抛异常且点名缺哪个参数() {
        // 旧行为是把 {orderId} 字面量发出去（服务端必然 404，LLM 收到 404 无从判断原因）。
        // 新行为不发请求，直接把「缺少必填参数 orderId」经 HttpToolCallback 的 catch 返回给
        // LLM，它能据此补参数重试。
        assertThatThrownBy(() -> UrlTemplateFiller.fill(
                "https://api.example.com/orders/{orderId}", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orderId");
    }

    @Test
    void 无占位符的URL原样返回() {
        String url = UrlTemplateFiller.fill("https://api.example.com/health", Map.of());

        assertThat(url).isEqualTo("https://api.example.com/health");
    }

    // ===== query 段 =====

    @Test
    void 可选query参数未填时整段删除() {
        String url = UrlTemplateFiller.fill(
                "https://api.example.com/search?q={q}&limit={limit}", Map.of("q", "鞋"));

        assertThat(url).isEqualTo("https://api.example.com/search?q=%E9%9E%8B");
    }

    @Test
    void query参数全部未填时连问号一起去掉() {
        String url = UrlTemplateFiller.fill(
                "https://api.example.com/search?q={q}&limit={limit}", Map.of());

        assertThat(url).isEqualTo("https://api.example.com/search");
    }

    @Test
    void 段内任一占位符缺值即删整段() {
        // range=5- 这种半填的值几乎肯定比不传更糟
        String url = UrlTemplateFiller.fill(
                "https://api.example.com/items?range={from}-{to}&q={q}",
                Map.of("from", "5", "q", "x"));

        assertThat(url).isEqualTo("https://api.example.com/items?q=x");
    }

    @Test
    void 无占位符的静态query段always保留() {
        String url = UrlTemplateFiller.fill(
                "https://api.example.com/search?apiKey=STATIC&q={q}", Map.of());

        assertThat(url).isEqualTo("https://api.example.com/search?apiKey=STATIC");
    }

    @Test
    void baseUrl自带query时后续段正常处理() {
        String url = UrlTemplateFiller.fill(
                "https://api.example.com/api?v=1&q={q}", Map.of("q", "x"));

        assertThat(url).isEqualTo("https://api.example.com/api?v=1&q=x");
    }

    // ===== 编码与注入 =====

    @Test
    void 参数值含花括号不会引发二次替换() {
        // 这条钉的是「现状就成立」的正确性：URLEncoder 把 { } 编码成 %7B %7D，所以先替换
        // 进去的值不可能被后面的替换轮次再次识别成占位符。将来若有人「优化」掉编码，
        // 本条会红。
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("a", "{b}");
        args.put("b", "SECRET");

        String url = UrlTemplateFiller.fill("https://api.example.com/x/{a}/{b}", args);

        assertThat(url).isEqualTo("https://api.example.com/x/%7Bb%7D/SECRET");
    }

    @Test
    void 参数值含与号不会拆出新的query段() {
        String url = UrlTemplateFiller.fill(
                "https://api.example.com/search?q={q}", Map.of("q", "a&admin=true"));

        assertThat(url).isEqualTo("https://api.example.com/search?q=a%26admin%3Dtrue");
    }

    @Test
    void 多余的args被忽略不影响URL() {
        // POST 时这些多余参数会进 body，这里只管 URL
        String url = UrlTemplateFiller.fill(
                "https://api.example.com/orders/{id}", Map.of("id", "1", "note", "x"));

        assertThat(url).isEqualTo("https://api.example.com/orders/1");
    }

    @Test
    void null值按字面量null填入而不抛空指针() {
        Map<String, Object> args = new HashMap<>();
        args.put("id", null);

        String url = UrlTemplateFiller.fill("https://api.example.com/orders/{id}", args);

        assertThat(url).isEqualTo("https://api.example.com/orders/null");
    }
}
