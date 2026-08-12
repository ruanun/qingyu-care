package com.qingyu.care.config;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 共享行凭据脱敏：非平台上下文读到平台共享行（{@code tenant_id IS NULL}）时，把凭据字段置空。
 *
 * <p><b>判据复用 {@link TenantOwnershipGuard#mutableInCurrentContext}</b>——「能修改它」与
 * 「能看它的凭据」是同一个权限边界，不新造概念。该守卫对平台上下文（{@code tenantId} 为空）
 * 放行，对租户上下文读平台共享行返回 false，正是需要的语义。
 *
 * <p><b>为什么是 {@code @Component} 而不是像 {@link TenantOwnershipGuard} 那样的静态工具类</b>：
 * 脱敏前必须 {@code detach}，需要注入 {@link EntityManager}。这是被下面那条约束逼出来的，
 * 不是随意偏离既有风格。
 *
 * <p>⚠️ <b>必须先 detach 再置空。</b>本项目未显式配置 {@code spring.jpa.open-in-view}，
 * 即 Spring Boot 默认的 {@code true}：OSIV 让 EntityManager 在整个请求期间保持打开，
 * list 查询返回的实体一直是<b>受管</b>状态。直接对受管实体 setter 置空会被 Hibernate 脏检查
 * 当成真实修改，后续同一请求内任何事务提交都可能把 {@code null} 刷回库——本意是防止凭据
 * 泄漏，结果是把凭据<b>真的删掉</b>。detach 后该对象脱离持久化上下文，置空只影响即将序列化
 * 给前端的这份数据。
 *
 * <p>⚠️ <b>只能用在面向租户的管理面 list 出口</b>（{@code ChatToolService.listTools} /
 * {@code ModelRouterService.getAllModels}）。<b>绝不能用在运行时解析路径</b>——
 * {@code HttpToolCallback} 要拿真凭据发出站请求，脱敏误伤会让共享工具静默失效：第三方返 401，
 * 模型拿到一段错误响应继续编答案，全程无异常无日志。
 *
 * @author ruan
 */
@Component
public class SharedCredentialMasker {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 就地脱敏列表中当前上下文无权查看凭据的元素。
     *
     * @param list             待处理列表（就地修改并原样返回）
     * @param tenantIdGetter   取元素的 tenant_id
     * @param appIdGetter      取元素的 app_id
     * @param credentialCleaner 清空该元素凭据字段的动作（如 {@code t -> t.setAuthHeader(null)}）
     * @return 入参 list 本身
     */
    public <T> List<T> mask(List<T> list,
                            Function<T, Long> tenantIdGetter,
                            Function<T, Long> appIdGetter,
                            Consumer<T> credentialCleaner) {
        for (T item : list) {
            if (TenantOwnershipGuard.mutableInCurrentContext(
                    tenantIdGetter.apply(item), appIdGetter.apply(item))) {
                continue;   // 有权查看，原样保留
            }
            entityManager.detach(item);      // 先脱离持久化上下文，见类注释
            credentialCleaner.accept(item);
        }
        return list;
    }
}
