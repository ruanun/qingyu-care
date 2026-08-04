package com.qingyu.care.dto;

import com.qingyu.care.enums.SyncInterval;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 添加网页文档的请求体。
 *
 * @author ruan
 */
@Data
public class AddWebPageRequest {

    /** 只接受 http/https，与前端 safeUrl 白名单一致；其余协议直接被校验挡下。 */
    @NotBlank(message = "URL 不能为空")
    @Pattern(regexp = "^https?://.+", message = "只支持 http/https 开头的网址")
    private String url;

    /** 同步周期；不传按 MANUAL 处理。 */
    private SyncInterval syncInterval;
}
