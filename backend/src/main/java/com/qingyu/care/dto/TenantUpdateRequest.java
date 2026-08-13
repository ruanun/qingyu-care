package com.qingyu.care.dto;

import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.enums.TenantStatus;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户局部更新请求。
 *
 * <p><b>为什么不直接用 {@code Tenant} 实体收请求体</b>：实体字段带 Lombok
 * {@code @Builder.Default}（{@code status}/{@code plan}），而 Lombok 会把默认值也应用进无参
 * 构造，Jackson 反序列化走的正是那个无参构造——于是请求体里<b>没给</b>的字段拿到的是类默认值
 * 而非 {@code null}，{@code if (x != null)} 恒真，把库里的值覆盖掉。
 *
 * <p>⚠️ <b>{@code plan} 是本次修复里后果最重的另一个</b>：实体默认 {@code TenantPlan.FREE}，
 * 所以在租户页改一下状态（冻结/启用）就会把套餐静默打回 FREE，直接影响计费额度。
 *
 * <p>⚠️ <b>本类所有字段一律不写初始化器、不加 {@code @Builder.Default}、不加
 * {@code @Builder}</b>——{@code null} 必须真正表示「请求体没给这个字段」。加了 builder 就会
 * 诱使下一个人写 {@code @Builder.Default}，把坑重新种回来。反例见
 * {@code KnowledgeBaseRequest.enabled}（它现在没出事只因为前端建/改知识库都发整份对象）。
 *
 * @author ruan
 */
@Data
public class TenantUpdateRequest {
    private String name;
    private String contactName;
    private String contactPhone;
    private String contactEmail;
    private TenantStatus status;
    private TenantPlan plan;
    private LocalDateTime expireTime;
}
