package com.qingyu.care.service.openapi;

import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.service.web.WebPageFetcher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 从 URL 取 schema 文本。**不自己建 HTTP 客户端**——整条出站防线（SSRF 钉 IP + 禁重定向
 * + 超时 + 5MB 上限）复用 {@link WebPageFetcher#downloadRaw}，只换 Content-Type 白名单。
 *
 * <p>新开一条抓取路径就等于新开一个 SSRF 缺口，这是网站知识库那边就立下的规矩。
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
public class OpenApiSchemaFetcher {

    /**
     * schema 常见的类型。⚠️ 含 {@code text/plain} 是因为 GitHub raw 用它下发；
     * 不含 {@code text/html}——那多半意味着填的是文档页而不是 schema 本身。
     */
    static final Set<String> SCHEMA_TYPES = Set.of(
            "application/json", "application/yaml", "application/x-yaml",
            "text/yaml", "text/x-yaml", "text/plain");

    private final WebPageFetcher webPageFetcher;

    public String fetch(String url) {
        WebPageFetcher.Downloaded downloaded;
        try {
            downloaded = webPageFetcher.downloadRaw(url, SCHEMA_TYPES);
        } catch (BusinessException e) {
            // 禁重定向是硬安全取舍，而会 302 的 schema URL 很常见——统一补一句可操作的提示，
            // 而不是把「网页返回 HTTP 302」原样丢给用户去猜。
            throw new BusinessException("拉取 schema 失败：" + e.getMessage()
                    + "。若该地址会跳转（3xx），请直接填写跳转后的最终地址");
        }
        Charset charset = downloaded.charsetName() == null
                ? StandardCharsets.UTF_8
                : Charset.forName(downloaded.charsetName());
        return new String(downloaded.body(), charset);
    }
}
