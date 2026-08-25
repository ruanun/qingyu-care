package com.qingyu.care.dto;

import com.qingyu.care.entity.AbExperiment;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.entity.McpServer;
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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 扫描式守护「{@code *UpdateRequest} 字段覆盖」。
 *
 * <p>⚠️ <b>直觉上的规则（DTO 字段集 ⊇ 实体可变字段集）照字面实现是错的</b>：实测有 6 个
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
 * 这类缺陷的病根正是「实体加了字段，DTO 没跟上，且没人发现」。新增一个类名以 {@code UpdateRequest}
 * 结尾的 DTO 并在 {@link #DTO_TO_ENTITY} 登记对应实体、或给 {@link #DTO_TO_ENTITY} 里已登记的
 * 那 8 个实体之一加一个新的可变字段，本类都会红，除非显式补 DTO 字段或白名单条目。
 *
 * <p>⚠️ <b>覆盖边界是类名后缀约定 + 显式登记，不是「任何实体」</b>：只有类名以
 * {@code UpdateRequest} 结尾、且在 {@link #DTO_TO_ENTITY} 里显式登记过对应实体的类才在扫描
 * 范围内。{@code KnowledgeBase}/{@code CrawlSite}/{@code QaPair} 等没有对应 {@code *UpdateRequest}
 * DTO（或走别的命名/收参方式）的实体，本类完全不管——加字段不会红，也不代表安全，只是不在
 * 本条守护的覆盖范围里。
 * ⚠️ 这也是本类<b>最现实的绕过方式</b>：不必往白名单里编造理由，只要把新 DTO 起个不以
 * {@code UpdateRequest} 结尾的名字就完全绕开了（{@code KnowledgeBaseRequest}、
 * {@code CrawlSiteRequest}、{@code QaPairRequest}、{@code UpdateRuleRequest} 这四条 update
 * 路径现在就在边界外）。要根治得换成「每个有 PUT 端点、请求体是专用 DTO 的实体都必须登记」，
 * 而不是靠类名后缀去发现。
 *
 * <p><b>本类只守住「实体 → DTO」这一跳</b>；「DTO → {@code if (x != null) existing.setX(...)}」
 * 那一跳由 {@code UpdateRequestPropagationTest} 守（技术债第 18 项，已补）。两者缺一不可：
 * 给实体和 DTO 都加了字段、却漏掉那行赋值时，<b>本类全绿</b>而症状完全静默。
 * 实测印证过——把 {@code PlatformController.updateSharedTool} 的一行 setter 删掉，全仓 713 条
 * 测试里只有传播哨兵那一条转红。
 *
 * <p>白名单本身也不能腐烂：{@link #whitelist条目必须仍然对得上实体与DTO的真实差集()} 反向断言
 * 白名单里的每条声明现在依然成立（字段确实在实体上、确实不在 DTO 上）——否则它会变成一张没人
 * 敢删的死名单。
 *
 * <p><b>排除理由是「类别 + 说明」两半</b>（2026-08-25 补，原技术债第 19 项）：自由文本理由不被
 * 任何机制校验，长期看会退化成逃生舱——把本该收进 DTO 的字段塞进白名单、随手编一句话，测试就
 * 绿了。{@link ExclusionCategory} 把这一步变成「必须挑一个评审能对着<b>字段性质</b>核验的分类」，
 * {@link #每个类别都必须至少有一条白名单条目在用()} 再防止类别集合自己长出没人用的死取值。
 * ⚠️ 技术债原文建议的四个类别（凭据专用端点 / 归属 / 身份键 / 系统维护生命周期）<b>不够用</b>：
 * 三处 {@code scope}（提权面）与 {@code KnowledgeBase.enabled}（有专用端点但不是凭据）都落不
 * 进去，照字面实现只会逼人硬塞、制造错误分类——与上面第一段说的「直觉规则照字面实现是错的」同源。
 *
 * <p>⚠️ <b>这道机制只约束分类，不能证明「这个字段本来就不该进 DTO」</b>。它可接受的前提是滥用
 * 方向本身是保守的：往白名单塞字段 = 声明「该字段不可通过 update 设置」，后果是功能缺陷（字段
 * 改不动，凭据轮换时才发现），不是安全缺陷。真正危险的方向（本不该可设的字段变得可设）根本不
 * 经过白名单——它由「DTO 里有没有这个字段」决定，而往 DTO 加字段是显式的、评审一眼可见。
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
    static final Map<Class<?>, Class<?>> DTO_TO_ENTITY = Map.of(
            ApplicationUpdateRequest.class, Application.class,
            TenantUpdateRequest.class, Tenant.class,
            UserUpdateRequest.class, User.class,
            AbTestUpdateRequest.class, AbExperiment.class,
            AiModelConfigUpdateRequest.class, AiModelConfig.class,
            AiToolUpdateRequest.class, AiTool.class,
            AiSkillUpdateRequest.class, AiSkill.class,
            ChatStrategyUpdateRequest.class, ChatStrategy.class,
            KnowledgeBaseUpdateRequest.class, KnowledgeBase.class,
            McpServerUpdateRequest.class, McpServer.class);

    /**
     * 刻意排除的类别。白名单每条都必须挑一个。
     *
     * <p>它解决的是自由文本理由的退化风险：理由不被任何机制校验，图省事的人可以把本该收进 DTO
     * 的字段塞进白名单、随手编一句话，测试就绿了。类别把「编一句话」变成「挑一个评审能对着
     * <b>字段性质</b> 核验的分类」——分类选错，对着字段看一眼就能发现；自由文本编得再漂亮也
     * 没有可核验的锚点。
     *
     * <p>⚠️ 类别不替代 {@link Exclusion#note()}：类别表达不了「是哪个端点」「哪次事故」，那些
     * 仍然要写在 note 里。
     *
     * <p>⚠️ 新增取值前先确认它真有条目要用——{@link #每个类别都必须至少有一条白名单条目在用()}
     * 会拦下没人用的空类别。类别集合一旦长出死取值，就会变成下一张「没人敢删的死名单」，正是
     * 白名单自己配了反向断言要防的那件事。
     */
    enum ExclusionCategory {
        /** 凭据字段，另有专用的重置/轮换端点承担写入。判据：字段是密钥/密码类，且能指出那个端点。 */
        CREDENTIAL_DEDICATED_ENDPOINT,
        /** 归属关系字段，客户端可设即跨租户写入。判据：字段类型是所属实体（如 {@code Tenant}）。 */
        OWNERSHIP,
        /** 身份键，事实上不可变。判据：全仓没有任何更新路径调用它的 setter，改了等于换一个对象。 */
        IDENTITY_KEY,
        /** 服务端在业务流程中写入的状态/时间戳，客户端无从知晓其正确值。判据：能指出写它的那行代码。 */
        SYSTEM_MANAGED,
        /** 生命周期状态或其时间戳，由专用端点驱动。判据：能指出那个端点，且经 update 改会绕过它的校验。 */
        LIFECYCLE_DEDICATED_ENDPOINT,
        /** 决定可见范围的提权面，客户端自封会把自己的资源（连同其中的凭据）暴露给其他租户。 */
        PRIVILEGE_SCOPE
    }

    /**
     * 白名单条目：{@code category} 供评审对着字段性质核验，{@code note} 承载类别表达不了的具体
     * 信息（哪个端点、哪次事故、哪一行代码）。两者都必须有，不互相替代。
     */
    record Exclusion(ExclusionCategory category, String note) {
    }

    private static Exclusion ex(ExclusionCategory category, String note) {
        return new Exclusion(category, note);
    }

    /**
     * 白名单：DTO 简单类名 → （字段名 → 刻意排除的类别与说明）。必须能回答「为什么这个字段
     * 不该由客户端通过局部更新设置」。
     */
    private static final Map<String, Map<String, Exclusion>> EXCLUSION_WHITELIST = buildWhitelist();

    private static Map<String, Map<String, Exclusion>> buildWhitelist() {
        Map<String, Map<String, Exclusion>> whitelist = new HashMap<>();

        Map<String, Exclusion> application = new HashMap<>();
        application.put("appKey", ex(ExclusionCategory.IDENTITY_KEY,
                "应用身份标识，仅在 ApplicationController.create 创建时生成一次"
                        + "（ApplicationController.java:97），无任何更新路径调用"));
        application.put("appSecret", ex(ExclusionCategory.CREDENTIAL_DEDICATED_ENDPOINT,
                "凭据，专用端点 POST /{id}/regenerate-secret 重置（ApplicationController:144-145）"));
        application.put("webhookSecret", ex(ExclusionCategory.CREDENTIAL_DEDICATED_ENDPOINT,
                "凭据，专用端点 POST /{id}/regenerate-webhook-secret 重置（ApplicationController:158-159）"));
        application.put("tenant", ex(ExclusionCategory.OWNERSHIP,
                "归属关系，客户端可设=跨租户写入（技术债第14项刚堵掉的那类洞）"));
        whitelist.put("ApplicationUpdateRequest", application);

        Map<String, Exclusion> tenant = new HashMap<>();
        tenant.put("code", ex(ExclusionCategory.IDENTITY_KEY,
                "租户身份键，全仓无任何 setCode 调用，事实上不可变"));
        whitelist.put("TenantUpdateRequest", tenant);

        Map<String, Exclusion> user = new HashMap<>();
        user.put("username", ex(ExclusionCategory.IDENTITY_KEY,
                "登录用户名，身份标识，事实上不可变"));
        user.put("tenant", ex(ExclusionCategory.OWNERSHIP,
                "归属关系，客户端可设=跨租户写入"));
        user.put("lastLoginTime", ex(ExclusionCategory.SYSTEM_MANAGED,
                "系统维护字段，由 AuthService 登录成功时写入（AuthService.java:96）"));
        whitelist.put("UserUpdateRequest", user);

        Map<String, Exclusion> abTest = new HashMap<>();
        abTest.put("status", ex(ExclusionCategory.LIFECYCLE_DEDICATED_ENDPOINT,
                "生命周期状态，由 start/end 两个专用端点驱动（DRAFT→RUNNING→ENDED），"
                        + "update 不该绕过生命周期校验直接改状态"));
        abTest.put("startedAt", ex(ExclusionCategory.LIFECYCLE_DEDICATED_ENDPOINT,
                "生命周期时间戳，由 AbTestService.start 写入（AbTestService.java:131-132）"));
        abTest.put("endedAt", ex(ExclusionCategory.LIFECYCLE_DEDICATED_ENDPOINT,
                "生命周期时间戳，由 AbTestService.end 写入（AbTestService.java:143-144）"));
        whitelist.put("AbTestUpdateRequest", abTest);

        Map<String, Exclusion> aiModelConfig = new HashMap<>();
        aiModelConfig.put("scope", ex(ExclusionCategory.PRIVILEGE_SCOPE,
                "客户端可控字段，租户曾可在请求体里自封 PLATFORM 让自己的模型（连同"
                        + "自己的 apiKey）出现在所有租户列表里；平台共享模型只能走 /v1/platform/models"));
        whitelist.put("AiModelConfigUpdateRequest", aiModelConfig);

        Map<String, Exclusion> aiTool = new HashMap<>();
        aiTool.put("scope", ex(ExclusionCategory.PRIVILEGE_SCOPE,
                "客户端可控字段，租户曾可在请求体里自封 PLATFORM 让自己的工具（连同自己的"
                        + "鉴权头）出现在所有租户列表里；平台共享工具只能走 /v1/platform/tools"));
        whitelist.put("AiToolUpdateRequest", aiTool);

        Map<String, Exclusion> knowledgeBase = new HashMap<>();
        knowledgeBase.put("enabled", ex(ExclusionCategory.LIFECYCLE_DEDICATED_ENDPOINT,
                "启用状态有 PUT /{id}/toggle 与 PUT /{id}/enabled 两个专用端点；"
                        + "它曾经在改名字这条路径上被静默打回 true（DTO 的 enabled 写了 = true 初始化器使判空恒真），"
                        + "停用的知识库因此会被一次改名重新启用"));
        whitelist.put("KnowledgeBaseUpdateRequest", knowledgeBase);

        Map<String, Exclusion> mcpServer = new HashMap<>();
        mcpServer.put("scope", ex(ExclusionCategory.PRIVILEGE_SCOPE,
                "客户端可控字段，同 AiTool.scope/AiModelConfig.scope 的既有教训——本期恒为 "
                        + "TENANT，为将来平台共享预留，不放入 DTO 防止将来放开后重蹈自封 PLATFORM 的覆辙"));
        mcpServer.put("lastSyncedAt", ex(ExclusionCategory.SYSTEM_MANAGED,
                "同步产生的系统时间戳，由 McpServerService.sync 同步成功时写入，"
                        + "不该由客户端 PUT 设置"));
        mcpServer.put("lastSyncError", ex(ExclusionCategory.SYSTEM_MANAGED,
                "同步产生的系统状态字段，由 McpServerService.sync 写入/清空，"
                        + "不该由客户端 PUT 设置"));
        whitelist.put("McpServerUpdateRequest", mcpServer);

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
            Map<String, Exclusion> whitelist =
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

    /**
     * 白名单说明的最小长度：单靠它挡不住「随手编一句话」（那半由 {@link ExclusionCategory} 挡），
     * 但挡得住留空/占位符这种最偷懒的情况。
     */
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
        for (Map.Entry<String, Map<String, Exclusion>> dtoEntry : EXCLUSION_WHITELIST.entrySet()) {
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

            for (Map.Entry<String, Exclusion> fieldEntry : dtoEntry.getValue().entrySet()) {
                String field = fieldEntry.getKey();
                Exclusion exclusion = fieldEntry.getValue();
                String note = exclusion.note();

                if (exclusion.category() == null) {
                    invalidEntries.add(dtoSimpleName + "." + field
                            + " 没有声明 ExclusionCategory——类别是让评审对着字段性质核验的那一半，不能省");
                }
                if (note == null || note.isBlank() || note.trim().length() < MIN_REASON_LENGTH) {
                    invalidEntries.add(dtoSimpleName + "." + field + " 的排除说明为空或过短（少于 "
                            + MIN_REASON_LENGTH + " 个字符）——类别表达不了「是哪个端点、哪次事故」，"
                            + "那部分必须写在 note 里，不能留空/占位符");
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
                .as("以下白名单条目已经名不副实（字段已补进 DTO、实体上已经没有这个字段，或类别/说明缺失），"
                        + "白名单会因此越积越多变成死名单，或退化成没人读的摆设——请修正或删除对应条目")
                .isEmpty();
    }

    @Test
    @DisplayName("每个 ExclusionCategory 取值都必须至少有一条白名单条目在用，否则类别集合会长出死取值")
    void 每个类别都必须至少有一条白名单条目在用() {
        Set<ExclusionCategory> used = EnumSet.noneOf(ExclusionCategory.class);
        for (Map<String, Exclusion> byField : EXCLUSION_WHITELIST.values()) {
            for (Exclusion exclusion : byField.values()) {
                if (exclusion.category() != null) {
                    used.add(exclusion.category());
                }
            }
        }

        // 白名单整个空掉（或全部条目都没写类别）时，下面的差集断言会退化成「全集减空集必须为空」
        // 而稳定报错——那是对的方向；但反过来若有人把断言写反，空转就成了永真。先把「确实扫到了
        // 在用的类别」钉住，与本类另外两条测试的防空转断言同源。
        assertThat(used)
                .as("白名单里一条带类别的条目都没有——要么白名单空了，要么类别全没写，"
                        + "此时类别机制形同虚设")
                .isNotEmpty();

        Set<ExclusionCategory> unused = EnumSet.allOf(ExclusionCategory.class);
        unused.removeAll(used);

        assertThat(unused)
                .as("以下 ExclusionCategory 取值没有任何白名单条目在用。类别的意义是「让评审对着"
                        + "字段性质核验」，没人用的取值只会扩大可挑选的面、给「随手挑一个」提供空间，"
                        + "并重蹈白名单变死名单那条——要么删掉该取值，要么说明它服务于哪个真实条目")
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
