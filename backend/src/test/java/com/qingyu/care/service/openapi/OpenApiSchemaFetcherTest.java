package com.qingyu.care.service.openapi;

import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.service.web.WebPageFetcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * schema 拉取：复用网页抓取那条出站防线，只换 Content-Type 白名单。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class OpenApiSchemaFetcherTest {

    @Mock
    private WebPageFetcher webPageFetcher;

    @InjectMocks
    private OpenApiSchemaFetcher fetcher;

    @Test
    void 按声明的字符集解码正文() {
        when(webPageFetcher.downloadRaw(eq("https://x.com/openapi.json"), any()))
                .thenReturn(new WebPageFetcher.Downloaded(
                        "{\"openapi\":\"3.0.0\"}".getBytes(StandardCharsets.UTF_8), "UTF-8", 200));

        String text = fetcher.fetch("https://x.com/openapi.json");

        assertThat(text).contains("3.0.0");
    }

    @Test
    void 未声明字符集时按UTF8解码() {
        when(webPageFetcher.downloadRaw(any(), any()))
                .thenReturn(new WebPageFetcher.Downloaded(
                        "标题".getBytes(StandardCharsets.UTF_8), null, 200));

        assertThat(fetcher.fetch("https://x.com/openapi.json")).isEqualTo("标题");
    }

    @Test
    void 传给下层的白名单包含json与yaml且不含html() {
        when(webPageFetcher.downloadRaw(any(), any()))
                .thenReturn(new WebPageFetcher.Downloaded("{}".getBytes(StandardCharsets.UTF_8), null, 200));

        fetcher.fetch("https://x.com/openapi.json");

        assertThat(OpenApiSchemaFetcher.SCHEMA_TYPES)
                .contains("application/json", "application/yaml", "text/plain")
                .doesNotContain("text/html");
    }

    @Test
    void 下层失败时补上跳转提示() {
        // 禁重定向是硬安全取舍（重定向目标不再经 SsrfGuard 校验），而会 302 的 schema URL
        // 在真实世界很常见，只能靠消息质量补偿——否则用户只看到「网页返回 HTTP 302」，
        // 完全不知道该怎么办。
        when(webPageFetcher.downloadRaw(any(), any()))
                .thenThrow(new BusinessException("网页返回 HTTP 302"));

        assertThatThrownBy(() -> fetcher.fetch("https://x.com/openapi.json"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("302")
                .hasMessageContaining("最终地址");
    }
}
