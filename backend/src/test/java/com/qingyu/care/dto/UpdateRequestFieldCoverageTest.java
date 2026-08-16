package com.qingyu.care.dto;

import com.qingyu.care.entity.AbExperiment;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 扫描式守护「{@code *UpdateRequest} 字段覆盖」，技术债清单第 16 项。
 *
 * <p>⚠️ <b>第 16 项原描述的规则（DTO 字段集 ⊇ 实体可变字段集）照字面实现是错的</b>：实测有 6 个
 * DTO 会红（{@code ApplicationUpdateRequest}/{@code TenantUpdateRequest}/
 * {@code UserUpdateRequest}/{@code AbTestUpdateRequest}/{@code AiModelConfigUpdateRequest}/
 * {@code AiToolUpdateRequest}），而每一处「遗漏」都是有意且正确的——凭据字段（{@code appSecret}/
 * {@code webhookSecret}/{@code scope}）有专用端点或结构性理由不该由客户端设，归属字段
 * （{@code tenant}）客户端可设即跨租户写入（技术债第 14 项刚堵掉的那类洞），身份键
 * （{@code code}/{@code username}）事实上不可变，生命周期字段（{@code status}/{@code startedAt}/
 * {@code endedAt}/{@code lastLoginTime}）由专用端点或系统维护写入。强行要求「收全」等于把凭据与
 * 归属关系重新变成客户端可设。
 *
 * <p>故本类实现的是修正后的规则：<b>差集里的每个字段必须在 {@link #EXCLUSION_WHITELIST} 里被
 * 显式声明并写明理由</b>。目标不是「不许漏」，而是把 <b>静默遗漏</b> 变成 <b>刻意排除</b>——
 * 第 12 项的病根正是「实体加了字段，DTO 没跟上，且没人发现」。新增一个类名以 {@code UpdateRequest}
 * 结尾的 DTO 并在 {@link #DTO_TO_ENTITY} 登记对应实体、或给 {@link #DTO_TO_ENTITY} 里已登记的
 * 那 8 个实体之一加一个新的可变字段，本类都会红，除非显式补 DTO 字段或白名单条目。
 *
 * <p>⚠️ <b>覆盖边界是类名后缀约定 + 显式登记，不是「任何实体」</b>：只有类名以
 * {@code UpdateRequest} 结尾、且在 {@link #DTO_TO_ENTITY} 里显式登记过对应实体的类才在扫描
 * 范围内。{@code KnowledgeBase}/{@code CrawlSite}/{@code QaPair} 等没有对应 {@code *UpdateRequest}
 * DTO（或走别的命名/收参方式）的实体，本类完全不管——加字段不会红，也不代表安全，只是不在
 * 本条守护的覆盖范围里。
 *
 * <p>白名单本身也不能腐烂：{@link #whitelist条目必须仍然对得上实体与DTO的真实差集()} 反向断言
 * 白名单里的每条声明现在依然成立（字段确实在实体上、确实不在 DTO 上）——否则它会变成一张没人
 * 敢删的死名单。
 *
 * <p>扫描范式与 {@code UpdateRequestNoInitializerTest} 同源，同样用
 * {@code ClassPathScanningCandidateComponentProvider} + 类名正则做 include filter。
 *
 * @author ruan
 */
class UpdateRequestFieldCoverageTest {

    private static final String DTO_PACKAGE = "com.qingyu.care.dto";

    /** 全局排除：由框架/服务端维护，任何 {@code *UpdateRequest} 都不该、也不能通过它们设置。 */
    private static final Set<String> GLOBAL_EXCLUDED_FIELDS =
            Set.of("id", "createdAt", "updatedAt", "tenantId", "appId");

    /**
     * DTO 类 → 对应实体类。显式列出而非按类名去掉 {@code UpdateRequest} 后缀推断——字符串推断在
     * 类名规则变化时会静默失配（如 {@code AbTestUpdateRequest} 对应的是 {@code AbExperiment}
     * 而非 {@code AbTest}）。
     */
    private static final Map<Class<?>, Class<?>> DTO_TO_ENTITY = Map.of(
            ApplicationUpdateRequest.class, Application.class,
            TenantUpdateRequest.class, Tenant.class,
            UserUpdateRequest.class, User.class,
            AbTestUpdateRequest.class, AbExperiment.class,
            AiModelConfigUpdateRequest.class, AiModelConfig.class,
            AiToolUpdateRequest.class, AiTool.class,
            AiSkillUpdateRequest.class, AiSkill.class,
            ChatStrategyUpdateRequest.class, ChatStrategy.class);

    /**
     * 白名单：DTO 简单类名 → （字段名 → 刻意排除的理由）。理由是给人读的，必须能回答
     * 「为什么这个字段不该由客户端通过局部更新设置」。
     */
    private static final Map<String, Map<String, String>> EXCLUSION_WHITELIST = buildWhitelist();

    private static Map<String, Map<String, String>> buildWhitelist() {
        Map<String, Map<String, String>> whitelist = new HashMap<>();

        Map<String, String> application = new HashMap<>();
        application.put("appKey", "应用身份标识，仅在 ApplicationController.create 创建时生成一次"
                + "（ApplicationController.java:97），无任何更新路径调用");
        application.put("appSecret", "凭据，专用端点 POST /{id}/regenerate-secret 重置（ApplicationController:144-145）");
        application.put("webhookSecret", "凭据，专用端点 POST /{id}/regenerate-webhook-secret 重置（ApplicationController:158-159）");
        application.put("tenant", "归属关系，客户端可设=跨租户写入（技术债第14项刚堵掉的那类洞）");
        whitelist.put("ApplicationUpdateRequest", application);

        Map<String, String> tenant = new HashMap<>();
        tenant.put("code", "租户身份键，全仓无任何 setCode 调用，事实上不可变");
        whitelist.put("TenantUpdateRequest", tenant);

        Map<String, String> user = new HashMap<>();
        user.put("username", "登录用户名，身份标识，事实上不可变");
        user.put("tenant", "归属关系，客户端可设=跨租户写入");
        user.put("lastLoginTime", "系统维护字段，由 AuthService 登录成功时写入（AuthService.java:96）");
        whitelist.put("UserUpdateRequest", user);

        Map<String, String> abTest = new HashMap<>();
        abTest.put("status", "生命周期状态，由 start/end 两个专用端点驱动（DRAFT→RUNNING→ENDED），"
                + "update 不该绕过生命周期校验直接改状态");
        abTest.put("startedAt", "生命周期时间戳，由 AbTestService.start 写入（AbTestService.java:131-132）");
        abTest.put("endedAt", "生命周期时间戳，由 AbTestService.end 写入（AbTestService.java:143-144）");
        whitelist.put("AbTestUpdateRequest", abTest);

        Map<String, String> aiModelConfig = new HashMap<>();
        aiModelConfig.put("scope", "客户端可控字段，租户曾可在请求体里自封 PLATFORM 让自己的模型（连同"
                + "自己的 apiKey）出现在所有租户列表里；平台共享模型只能走 /v1/platform/models");
        whitelist.put("AiModelConfigUpdateRequest", aiModelConfig);

        Map<String, String> aiTool = new HashMap<>();
        aiTool.put("scope", "客户端可控字段，租户曾可在请求体里自封 PLATFORM 让自己的工具（连同自己的"
                + "鉴权头）出现在所有租户列表里；平台共享工具只能走 /v1/platform/tools");
        whitelist.put("AiToolUpdateRequest", aiTool);

        return whitelist;
    }

    @Test
    @DisplayName("dto 包下所有 *UpdateRequest：实体可变字段与 DTO 字段的差集必须全部在白名单中声明理由")
    void 实体字段与DTO的差集必须全部在白名单中声明() {
        List<Class<?>> classes = scanUpdateRequestClasses();

        // 扫描逻辑本身坏掉时（包名改了、类名规则变了），下面的循环会直接空转、断言退化成
        // 「空==空」永真——UpdateRequestNoInitializerTest 已经踩过这个坑，这里同样先把
        // 「扫到的类数非空」显式钉成一条断言。
        assertThat(classes)
                .as("%s 包下一个 *UpdateRequest 类都没扫到——扫描逻辑本身坏了，下面的差集断言会全部空转",
                        DTO_PACKAGE)
                .isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (Class<?> dtoClass : classes) {
            Class<?> entityClass = DTO_TO_ENTITY.get(dtoClass);
            if (entityClass == null) {
                violations.add(dtoClass.getSimpleName()
                        + " 未在 DTO_TO_ENTITY 中登记对应实体类，请补充映射（新增 *UpdateRequest 时必须同步登记）");
                continue;
            }

            Set<String> dtoFields = fieldNames(dtoClass, Object.class);
            Set<String> entityFields = fieldNames(entityClass, Object.class);
            Map<String, String> whitelist =
                    EXCLUSION_WHITELIST.getOrDefault(dtoClass.getSimpleName(), Map.of());

            for (String entityField : entityFields) {
                if (GLOBAL_EXCLUDED_FIELDS.contains(entityField)) {
                    continue;
                }
                if (dtoFields.contains(entityField)) {
                    continue;
                }
                if (!whitelist.containsKey(entityField)) {
                    violations.add(dtoClass.getSimpleName() + " 未处理 " + entityClass.getSimpleName()
                            + "." + entityField + "：要么加进 DTO，要么加进 EXCLUSION_WHITELIST 并写明理由");
                }
            }
        }

        assertThat(violations)
                .as("以下实体字段既没进对应的 *UpdateRequest，也没在白名单里被显式声明理由——"
                        + "这是一次静默遗漏，必须变成刻意排除或补进 DTO")
                .isEmpty();
    }

    /** 白名单理由的最小长度：挡不住「随手编个理由」，但挡得住留空/占位符这种最偷懒的情况。 */
    private static final int MIN_REASON_LENGTH = 8;

    @Test
    @DisplayName("白名单条目必须仍然对得上实体与 DTO 的真实差集、且理由非空达标，否则说明白名单过时或摆设")
    void whitelist条目必须仍然对得上实体与DTO的真实差集() {
        List<Class<?>> classes = scanUpdateRequestClasses();

        assertThat(classes)
                .as("%s 包下一个 *UpdateRequest 类都没扫到——扫描逻辑本身坏了，白名单核对会全部空转",
                        DTO_PACKAGE)
                .isNotEmpty();

        Map<String, Class<?>> dtoBySimpleName = new HashMap<>();
        for (Class<?> dtoClass : classes) {
            dtoBySimpleName.put(dtoClass.getSimpleName(), dtoClass);
        }

        List<String> invalidEntries = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> dtoEntry : EXCLUSION_WHITELIST.entrySet()) {
            String dtoSimpleName = dtoEntry.getKey();
            Class<?> dtoClass = dtoBySimpleName.get(dtoSimpleName);
            if (dtoClass == null) {
                invalidEntries.add("白名单里的 " + dtoSimpleName + " 已不在 dto 包扫描结果中，该整条应删除");
                continue;
            }

            Class<?> entityClass = DTO_TO_ENTITY.get(dtoClass);
            Set<String> dtoFields = fieldNames(dtoClass, Object.class);
            Set<String> entityFields =
                    entityClass == null ? Set.of() : fieldNames(entityClass, Object.class);

            for (Map.Entry<String, String> fieldEntry : dtoEntry.getValue().entrySet()) {
                String field = fieldEntry.getKey();
                String reason = fieldEntry.getValue();

                if (reason == null || reason.isBlank() || reason.trim().length() < MIN_REASON_LENGTH) {
                    invalidEntries.add(dtoSimpleName + "." + field + " 的排除理由为空或过短（少于 "
                            + MIN_REASON_LENGTH + " 个字符）——白名单的理由是给人读的，不能留空/占位符");
                }
                if (dtoFields.contains(field)) {
                    invalidEntries.add(dtoSimpleName + "." + field
                            + " 已经进了 DTO，白名单条目已过时，应删除");
                } else if (!entityFields.contains(field)) {
                    invalidEntries.add((entityClass == null ? "?" : entityClass.getSimpleName()) + "." + field
                            + " 在实体上已不存在，" + dtoSimpleName + " 的白名单条目已过时，应删除");
                }
            }
        }

        assertThat(invalidEntries)
                .as("以下白名单条目已经名不副实（字段已补进 DTO、实体上已经没有这个字段，或理由为空/过短），"
                        + "白名单会因此越积越多变成死名单，或退化成没人读的摆设——请修正或删除对应条目")
                .isEmpty();
    }

    /** 取 {@code clazz} 直至（不含）{@code stopAt} 的全部非 static/非合成的声明字段名，逐层向上。 */
    private static Set<String> fieldNames(Class<?> clazz, Class<?> stopAt) {
        Set<String> names = new LinkedHashSet<>();
        Class<?> current = clazz;
        while (current != null && current != stopAt) {
            for (Field field : current.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || field.isSynthetic()) {
                    continue;
                }
                names.add(field.getName());
            }
            current = current.getSuperclass();
        }
        return names;
    }

    /** 反射扫描 {@link #DTO_PACKAGE} 包下所有类名以 {@code UpdateRequest} 结尾的类。 */
    private static List<Class<?>> scanUpdateRequestClasses() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*UpdateRequest")));

        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(DTO_PACKAGE)) {
            String className = definition.getBeanClassName();
            try {
                classes.add(Class.forName(className));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("扫描到的类无法加载: " + className, e);
            }
        }
        return classes;
    }
}
