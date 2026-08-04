package com.qingyu.care.dto;

import com.qingyu.care.enums.SyncInterval;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 添加网页文档的请求体。
 *
 * @author ruan
 */
@Data
public class AddWebPageRequest {

    /**
     * 只接受 http/https，与前端 safeUrl 白名单一致；其余协议直接被校验挡下。
     * 长度上限对齐 {@code document.source_url} 列宽（2048），否则超长网址会一路走到
     * INSERT 才报 value too long，用户拿到的是不可读的 500 而不是校验提示。
     */
    @NotBlank(message = "URL 不能为空")
    @Size(max = 2048, message = "网址长度不能超过 2048 个字符")
    @Pattern(regexp = "^https?://.+", message = "只支持 http/https 开头的网址")
    private String url;

    /** 同步周期；不传按 MANUAL 处理。 */
    private SyncInterval syncInterval;
}
