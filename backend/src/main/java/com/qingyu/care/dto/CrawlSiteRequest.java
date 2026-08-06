package com.qingyu.care.dto;

import com.qingyu.care.enums.SyncInterval;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 建站点 / 改站点配置的请求体。
 *
 * <p>{@code maxPages}/{@code maxDepth} 的 {@code @Max} 只是给用户的即时反馈，
 * <b>真正的硬顶由服务端按 {@code SiteCrawlProperties} 钳制</b>——注解里的字面量
 * 无法读配置，改配置就会与它不一致，所以不能只靠它。
 *
 * @author ruan
 */
@Data
public class CrawlSiteRequest {

    /** 入口 URL。校验与 {@code AddWebPageRequest.url} 完全一致（同一列宽、同一协议白名单）。 */
    @NotBlank(message = "入口 URL 不能为空")
    @Size(max = 2048, message = "网址长度不能超过 2048 个字符")
    @Pattern(regexp = "^https?://.+", message = "只支持 http/https 开头的网址")
    private String entryUrl;

    /** 范围前缀；不传则由入口 URL 推导。host 必须与入口一致（服务端强制校验）。 */
    @Size(max = 2048, message = "范围前缀长度不能超过 2048 个字符")
    private String scopePrefix;

    /** 页数上限；不传用默认 50。 */
    @Min(value = 1, message = "页数上限至少为 1")
    @Max(value = 500, message = "页数上限不能超过 500")
    private Integer maxPages;

    /** 深度上限；不传用默认 3。入口页为深度 0。 */
    @Min(value = 0, message = "深度不能为负")
    @Max(value = 5, message = "深度不能超过 5")
    private Integer maxDepth;

    /** 正文 CSS 选择器；空则走启发式剔噪。 */
    @Size(max = 500, message = "选择器长度不能超过 500 个字符")
    private String contentSelector;

    /** 爬出的每篇文档的同步周期；不传按 MANUAL 处理。 */
    private SyncInterval syncInterval;
}
