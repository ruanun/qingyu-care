package com.qingyu.care.dto;

import lombok.Data;

/**
 * 供应商连接的局部更新请求。字段一律不写初始化器（null 表示「没给，不改」），理由见 {@code ChatStrategyUpdateRequest}。
 *
 * <p>⚠️ 刻意不含 tenantId / scope：归属由服务端决定，客户端自封 PLATFORM 是既有漏洞的老路数。
 * {@code apiKey} 为空串或全空格同样表示不修改（编辑弹窗不回显 Key）。
 *
 * @author ruan
 */
@Data
public class ModelProviderUpdateRequest {
    private String name;
    private String providerKey;
    private String endpoint;
    /** 留空表示不修改 */
    private String apiKey;
    private String remark;
}
