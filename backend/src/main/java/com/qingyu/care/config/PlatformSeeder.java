package com.qingyu.care.config;

import lombok.extern.slf4j.Slf4j;

/**
 * 平台默认配置行播种公共守卫：仅当表为空（count == 0）时执行播种动作，附统一日志。
 *
 * <p>各配置服务（{@code ChatStrategyService}/{@code HandoverRuleService}/
 * {@code PromptTemplateService}/{@code ModelRouterService}）的 {@code @PostConstruct init()}
 * 原本各自重复「{@code if (count == 0) { log; save...; log }}」样板，本类只收窄这层判空+日志，
 * **不介入播种内容**：具体 save 哪些行、builder 是否设置 tenantId/scope 仍由各服务内联决定
 * （均不显式设 tenantId → {@code @PostConstruct} 无 HTTP 请求线程，{@link TenantContext} 为空，
 * {@code TenantEntityListener} 不回填，落库即 {@code tenant_id IS NULL} 的平台默认行）。
 *
 * @author ruan
 */
@Slf4j
public final class PlatformSeeder {

    private PlatformSeeder() {
    }

    /**
     * 表为空时执行 seedAction 播种平台默认行；非空则跳过。
     *
     * @param count      当前表行数（调用方传 {@code repository.count()}）
     * @param label      用于日志的配置项名称（简体中文，如"问答策略"）
     * @param seedAction 播种动作，内联执行原有的 save 调用
     */
    public static void seedIfEmpty(long count, String label, Runnable seedAction) {
        if (count == 0) {
            log.info("初始化平台默认{}...", label);
            seedAction.run();
            log.info("平台默认{}初始化完成", label);
        }
    }
}
