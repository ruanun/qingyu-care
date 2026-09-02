package com.qingyu.care.config;

/**
 * 租户归属守卫：统一「按 id 加载后」的显式归属校验。
 *
 * <p>Hibernate 6 的 {@code @Filter} 只拦截查询（HQL/派生查询），不拦截 {@code findById}
 * （未设 {@code applyToLoadByKey}）。因此凡是走 {@code findById} 主键加载业务实体的服务，
 * 已登录租户都能枚举 id 跨租户读到他人数据——必须在加载后显式补这道校验，与「查不到」
 * 同语义处理（抛资源不存在），既堵越权也防枚举探测。
 *
 * <p>比对采用对称空容忍语义（范式同 86f6892 ChatFeedbackService / 3469510 AbTestService）：
 * <ul>
 *   <li>上下文租户与实体租户均非空且不相等 → 越权，返回 {@code false}；
 *   <li>上下文为空（平台管理员登录后 tenantId 恒为 null）→ 放行，天然豁免；
 *   <li>实体字段为空（{@code tenant_id IS NULL} 的平台共享资源）→ 放行，保住多级回退语义；
 *   <li>应用维度同理。
 * </ul>
 *
 * <h2>⚠️ 选哪个方法：看「这个实体的 tenant_id IS NULL 是不是有意共享」</h2>
 *
 * <p>取舍标准<b>不是</b>「读操作还是写操作」，也<b>不是</b>「带不带密钥」——那两个都是代理量，
 * 各自在某类实体上与真实语义脱钩过（见下）。真正的判据是<b>该实体存不存在「平台默认 / 平台
 * 共享」这一有意共享的语义</b>：
 *
 * <ul>
 *   <li><b>存在 → 用 {@link #belongsToCurrentContext}</b>。仅限配置类实体：{@code ChatStrategy}
 *       / {@code PromptTemplate} / {@code HandoverRule} / {@code AiModelConfig}。它们的 NULL 行
 *       由各服务 {@code @PostConstruct} <b>播种</b>而来，是「多级配置回退」的承重结构，租户必须
 *       读得到。</li>
 *   <li><b>不存在 → 用 {@link #mutableInCurrentContext}</b>。业务实体一律归此类：知识库 / 文档
 *       / QA / skill / 工具 / MCP / 附件 / 会话 / A/B 实验。它们<b>没有任何播种逻辑</b>，库里
 *       出现 NULL 行只有一个来源——<b>平台管理员在无租户上下文时建的行</b>，或本仓库反复出过的
 *       「tenantId 静默落 NULL」事故。这两种都<b>不是共享意图</b>，用读级守卫等于对全体租户开放。</li>
 * </ul>
 *
 * <p><b>这条判据是 2026-09-02 用真机越权实验 + 存量普查确立的</b>，此前踩过两次：
 * <ol>
 *   <li>聊天附件——平台管理员上传的附件 NULL 租户从无共享意图，却被任意租户读到；</li>
 *   <li>知识库文档——{@code requireDocumentOwnership} 被 get/delete/reprocess/status 四处共用，
 *       一处读级守卫同时放开了读、<b>删</b>、写：实测另一租户的管理员可读到平台私有文档正文、
 *       可将其<b>真的删除</b>、还可往平台私有知识库里插文档。</li>
 * </ol>
 * 两次都是同一条豁免换个实体复发。普查佐证：配置类表的 NULL 行成片存在（{@code handover_rules}
 * 3/3、{@code chat_strategy} 2/3、{@code prompt_templates} 1/1、{@code ai_model_config} 1/1），
 * 而业务实体表的 NULL 行<b>几乎为零</b>——正因为正常租户操作总带着上下文。
 *
 * @author ruan
 */
public final class TenantOwnershipGuard {

    private TenantOwnershipGuard() {
    }

    /**
     * 校验实体归属是否落在当前租户/应用上下文内。
     *
     * @param entityTenantId 实体的 tenant_id（可空）
     * @param entityAppId    实体的 app_id（可空）
     * @return 属于当前上下文或应放行时 true，明确越权时 false
     */
    public static boolean belongsToCurrentContext(Long entityTenantId, Long entityAppId) {
        Long ctxTenantId = TenantContext.getTenantId();
        if (ctxTenantId != null && entityTenantId != null && !ctxTenantId.equals(entityTenantId)) {
            return false;
        }
        Long ctxAppId = TenantContext.getAppId();
        return ctxAppId == null || entityAppId == null || ctxAppId.equals(entityAppId);
    }

    /**
     * 校验实体是否可在当前上下文中被修改/删除（写路径守卫）。
     *
     * <p>与 {@link #belongsToCurrentContext} 只差一点：平台共享行（tenant_id IS NULL）
     * 仅平台上下文（ctxTenantId 为空——平台管理员或系统内部线程）可写。读路径为保
     * 多级回退语义放行平台行，但写路径若同样放行，租户即可枚举 id 篡改/删除平台
     * 共享模型、平台默认策略/模板/规则，殃及全部租户。
     *
     * <p>含密钥的资源（如 AiModelConfig 的明文 apiKey）读路径也应使用本守卫，
     * 否则租户可经按 id 读取拿到平台凭据。
     *
     * @param entityTenantId 实体的 tenant_id（可空）
     * @param entityAppId    实体的 app_id（可空）
     * @return 当前上下文可写该实体时 true
     */
    public static boolean mutableInCurrentContext(Long entityTenantId, Long entityAppId) {
        Long ctxTenantId = TenantContext.getTenantId();
        if (ctxTenantId == null) {
            return true;
        }
        if (entityTenantId == null || !ctxTenantId.equals(entityTenantId)) {
            return false;
        }
        Long ctxAppId = TenantContext.getAppId();
        return ctxAppId == null || entityAppId == null || ctxAppId.equals(entityAppId);
    }
}
