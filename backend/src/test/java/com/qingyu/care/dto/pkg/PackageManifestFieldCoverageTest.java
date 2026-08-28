package com.qingyu.care.dto.pkg;

import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.McpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 扫描式守护「实体可变字段必须要么进 manifest、要么被显式声明为刻意排除」。
 *
 * <p><b>这是本特性方案唯一的结构性弱点</b>（spec §6.8 / §7）：导出时的字段抄写是手写的，
 * 会随实体演进漂移。实体加了字段、{@code PackageManifestBuilder} 没跟上，后果是<b>静默少装
 * 一个字段</b>——装出来的工具比平台调通的那个少一项配置，而包照常安装、界面照常显示、
 * 没有任何报错。
 *
 * <p>范式复刻 {@code UpdateRequestFieldCoverageTest}：目标不是「不许漏」，而是把<b>静默遗漏</b>
 * 变成<b>刻意排除</b>——差集里的每个字段都必须挑一个 {@link ExclusionCategory} 并写明说明。
 *
 * <p>⚠️ <b>为什么不复用 {@code UpdateRequestFieldCoverageTest.ExclusionCategory} 而是自己定义
 * 一份</b>（spec §7 说「连同它的 ExclusionCategory 枚举」，指的是那套<b>模式</b>而非同一个
 * Java 枚举）：那个类有一条自检 {@code 每个类别都必须至少有一条白名单条目在用()}，它扫的是
 * <b>它自己那张白名单</b>。两个哨兵共用一个枚举时，本类为 manifest 场景新增的类别（凭据升格、
 * 符号引用……）在那张白名单里一条都没有，那条自检会立刻转红——而且是<b>与它自己毫不相干的
 * 原因</b>。两类哨兵排除的是不同种类的字段，类别集合本来就该各自独立。
 *
 * <p>⚠️ <b>本类只守「实体 → manifest」这一跳。</b>「manifest → 安装时真的 setX」那一跳没有
 * 哨兵，由 {@code PackageInstallServiceTest} 的逐字段断言与 {@code PackageInstallIT} 覆盖
 * ——同 {@code UpdateRequestFieldCoverageTest} 与 {@code UpdateRequestPropagationTest} 的
 * 分工，两者缺一不可：manifest 里有这个字段、安装时漏了那行 setter，本类<b>全绿</b>而症状
 * 完全静默。
 *
 * @author ruan
 */
class PackageManifestFieldCoverageTest {

    /** 全局排除：主键与时间戳，任何 manifest 都不该带。 */
    private static final Set<String> GLOBAL_EXCLUDED_FIELDS =
            Set.of("id", "createdAt", "updatedAt", "tenantId", "appId");

    /** 实体类 → manifest 里对应的 record。 */
    private static final Map<Class<?>, Class<?>> ENTITY_TO_SPEC = Map.of(
            AiTool.class, PackageManifest.ToolSpec.class,
            AiSkill.class, PackageManifest.SkillSpec.class,
            McpServer.class, PackageManifest.McpServerSpec.class);

    /**
     * 刻意排除的类别。每条白名单条目必须挑一个。
     *
     * <p>类别把「随手编一句话」变成「挑一个评审能对着<b>字段性质</b>核验的分类」——分类选错，
     * 对着字段看一眼就能发现。
     */
    enum ExclusionCategory {
        /**
         * 凭据字段，导出时升格成 {@code secret} 变量，明文绝不进 manifest。
         * 判据：字段是密钥/鉴权头类，且 manifest 的对应 record 里有一个 {@code *Var} 字段接它。
         */
        CREDENTIAL_PROMOTED_TO_VARIABLE,
        /**
         * id 集合，manifest 里改用包内 key/名字表示（安装时目标行的 id 全新，抄 id 必然悬空）。
         * 判据：字段类型是 {@code Set<Long>} 之类的外键集合，且 record 里有对应的符号引用字段。
         */
        SYMBOLIC_REFERENCE,
        /**
         * 安装时由服务端强制取值，manifest 说了不算。
         * 判据：能指出安装代码里那行无条件的 {@code setX(...)}。
         */
        INSTALL_FORCED,
        /**
         * 运行中由系统写入的状态/时间戳，装出来是全新一行、天然为空。
         * 判据：能指出写它的那行代码。
         */
        SYSTEM_MANAGED
    }

    /** 白名单条目：{@code category} 供评审对着字段性质核验，{@code note} 承载具体信息。 */
    record Exclusion(ExclusionCategory category, String note) {
    }

    private static Exclusion ex(ExclusionCategory category, String note) {
        return new Exclusion(category, note);
    }

    /** 白名单：实体简单类名 →（字段名 → 排除的类别与说明）。 */
    private static final Map<String, Map<String, Exclusion>> EXCLUSION_WHITELIST = buildWhitelist();

    private static Map<String, Map<String, Exclusion>> buildWhitelist() {
        Map<String, Map<String, Exclusion>> whitelist = new LinkedHashMap<>();

        Map<String, Exclusion> aiTool = new LinkedHashMap<>();
        aiTool.put("authHeader", ex(ExclusionCategory.CREDENTIAL_PROMOTED_TO_VARIABLE,
                "明文凭据。导出时升格成 secret 变量，manifest 里只留 ToolSpec.authHeaderVar 指向它；"
                        + "导出明文等于把平台管理员的密钥随包发给每一个租户"));
        aiTool.put("enabled", ex(ExclusionCategory.INSTALL_FORCED,
                "安装产物一律 enabled=true（PackageInstallService.installStage1 里那行 setEnabled(true)）。"
                        + "「平台管理员当时把它停用了」是平台侧的临时状态，不该随包传播给租户"));
        aiTool.put("scope", ex(ExclusionCategory.INSTALL_FORCED,
                "安装时强制 OwnerScope.TENANT。manifest 里有这个字段就是把「租户自封 PLATFORM」"
                        + "那条真实事故（CLAUDE.md 平台共享工具第 1 条）换条路径复活"));
        whitelist.put("AiTool", aiTool);

        Map<String, Exclusion> aiSkill = new LinkedHashMap<>();
        aiSkill.put("toolIds", ex(ExclusionCategory.SYMBOLIC_REFERENCE,
                "指向 ai_tool 的 id 集合。安装时工具是新建行、id 全新，manifest 改用 "
                        + "SkillSpec.toolKeys 走包内符号，安装期间按 key→新 id 映射回填"));
        aiSkill.put("mcpToolIds", ex(ExclusionCategory.SYMBOLIC_REFERENCE,
                "指向 mcp_tool 的 id 集合。安装那一刻 mcp_tool 表里一行都没有（要先同步），"
                        + "manifest 改用 SkillSpec.mcpRefs 走「server key + 远端工具名」"));
        aiSkill.put("enabled", ex(ExclusionCategory.INSTALL_FORCED,
                "安装产物一律 enabled=true，同 AiTool.enabled"));
        whitelist.put("AiSkill", aiSkill);

        Map<String, Exclusion> mcpServer = new LinkedHashMap<>();
        mcpServer.put("authHeader", ex(ExclusionCategory.CREDENTIAL_PROMOTED_TO_VARIABLE,
                "明文凭据，同 AiTool.authHeader，升格成变量后由 McpServerSpec.authHeaderVar 指向"));
        mcpServer.put("enabled", ex(ExclusionCategory.INSTALL_FORCED,
                "安装产物一律 enabled=true，同 AiTool.enabled"));
        mcpServer.put("scope", ex(ExclusionCategory.INSTALL_FORCED,
                "安装时强制 OwnerScope.TENANT，同 AiTool.scope"));
        mcpServer.put("lastSyncedAt", ex(ExclusionCategory.SYSTEM_MANAGED,
                "同步产生的时间戳，由 McpServerService.sync 成功时写入。装出来是全新一行、"
                        + "还没同步过，抄平台那边的时间戳会让「从没同步过」看起来像「刚同步过」"));
        mcpServer.put("lastSyncError", ex(ExclusionCategory.SYSTEM_MANAGED,
                "同步产生的失败原因，由 McpServerService.sync 写入/清空，同 lastSyncedAt"));
        whitelist.put("McpServer", mcpServer);

        return whitelist;
    }

    @Test
    @DisplayName("实体可变字段与 manifest record 的差集必须全部在白名单中声明类别与理由")
    void 实体字段与manifest的差集必须全部在白名单中声明() {
        // 映射表空掉时下面的循环会空转、断言退化成「空==空」永真——先钉住它非空。
        assertThat(ENTITY_TO_SPEC)
                .as("ENTITY_TO_SPEC 是空的，下面的差集断言会全部空转")
                .isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (Map.Entry<Class<?>, Class<?>> entry : ENTITY_TO_SPEC.entrySet()) {
            Class<?> entityClass = entry.getKey();
            Class<?> specClass = entry.getValue();

            Set<String> specFields = fieldNames(specClass);
            Set<String> entityFields = fieldNames(entityClass);
            Map<String, Exclusion> whitelist =
                    EXCLUSION_WHITELIST.getOrDefault(entityClass.getSimpleName(), Map.of());

            // 实体字段一条都没读到 = 反射方式坏了，同样会让断言空转
            assertThat(entityFields)
                    .as("%s 上一个字段都没扫到——反射逻辑坏了", entityClass.getSimpleName())
                    .isNotEmpty();

            for (String entityField : entityFields) {
                if (GLOBAL_EXCLUDED_FIELDS.contains(entityField)
                        || specFields.contains(entityField)
                        || whitelist.containsKey(entityField)) {
                    continue;
                }
                violations.add(entityClass.getSimpleName() + "." + entityField + " 既没进 "
                        + specClass.getSimpleName() + "，也没在 EXCLUSION_WHITELIST 里被声明——"
                        + "这是一次静默遗漏：包照常安装、界面照常显示，只是装出来的行少了这项配置。"
                        + "要么加进 manifest（并同步改 PackageManifestBuilder 与 PackageInstallService），"
                        + "要么加进白名单并挑一个 ExclusionCategory");
            }
        }

        assertThat(violations).as("manifest 的字段覆盖有缺口").isEmpty();
    }

    /** 说明的最小长度：挡不住「随手编一句话」（那半由类别挡），但挡得住留空/占位符。 */
    private static final int MIN_NOTE_LENGTH = 8;

    @Test
    @DisplayName("白名单条目必须仍对得上实体与 manifest 的真实差集，且类别与说明齐备")
    void 白名单条目不得腐烂() {
        Map<String, Class<?>> entityBySimpleName = new LinkedHashMap<>();
        ENTITY_TO_SPEC.keySet().forEach(c -> entityBySimpleName.put(c.getSimpleName(), c));

        List<String> invalidEntries = new ArrayList<>();
        for (Map.Entry<String, Map<String, Exclusion>> entityEntry : EXCLUSION_WHITELIST.entrySet()) {
            String entitySimpleName = entityEntry.getKey();
            Class<?> entityClass = entityBySimpleName.get(entitySimpleName);
            if (entityClass == null) {
                invalidEntries.add("白名单里的 " + entitySimpleName
                        + " 已不在 ENTITY_TO_SPEC 中，该整条应删除");
                continue;
            }
            Set<String> entityFields = fieldNames(entityClass);
            Set<String> specFields = fieldNames(ENTITY_TO_SPEC.get(entityClass));

            for (Map.Entry<String, Exclusion> fieldEntry : entityEntry.getValue().entrySet()) {
                String field = fieldEntry.getKey();
                Exclusion exclusion = fieldEntry.getValue();

                if (exclusion.category() == null) {
                    invalidEntries.add(entitySimpleName + "." + field
                            + " 没有声明 ExclusionCategory——类别是让评审对着字段性质核验的那一半");
                }
                String note = exclusion.note();
                if (note == null || note.isBlank() || note.trim().length() < MIN_NOTE_LENGTH) {
                    invalidEntries.add(entitySimpleName + "." + field + " 的说明为空或过短（少于 "
                            + MIN_NOTE_LENGTH + " 字）——类别表达不了「是哪行代码、哪次事故」");
                }
                if (!entityFields.contains(field)) {
                    invalidEntries.add(entitySimpleName + "." + field
                            + " 在实体上已不存在，该条白名单已过时，应删除");
                } else if (specFields.contains(field)) {
                    invalidEntries.add(entitySimpleName + "." + field
                            + " 已经进了 manifest，该条白名单已过时，应删除");
                }
            }
        }

        assertThat(invalidEntries)
                .as("白名单条目已名不副实，留着会变成没人敢删的死名单，或掩盖一个真正的遗漏")
                .isEmpty();
    }

    @Test
    @DisplayName("每个 ExclusionCategory 取值都必须至少有一条白名单条目在用")
    void 每个类别都必须至少有一条条目在用() {
        Set<ExclusionCategory> used = EnumSet.noneOf(ExclusionCategory.class);
        for (Map<String, Exclusion> byField : EXCLUSION_WHITELIST.values()) {
            for (Exclusion exclusion : byField.values()) {
                if (exclusion.category() != null) {
                    used.add(exclusion.category());
                }
            }
        }

        assertThat(used)
                .as("白名单里一条带类别的条目都没有——此时类别机制形同虚设，下面的差集断言会空转")
                .isNotEmpty();

        Set<ExclusionCategory> unused = EnumSet.allOf(ExclusionCategory.class);
        unused.removeAll(used);

        assertThat(unused)
                .as("以下 ExclusionCategory 没有任何条目在用。没人用的取值只会扩大可挑选的面、"
                        + "给「随手挑一个」提供空间——要么删掉，要么说明它服务于哪个真实条目")
                .isEmpty();
    }

    /**
     * 取类自身及父类（到 Object 为止）的全部非 static、非合成字段名。
     *
     * <p>record 的组件在字节码里就是 private final 字段，同一套反射对 record 与实体都成立。
     */
    private static Set<String> fieldNames(Class<?> clazz) {
        Set<String> names = new LinkedHashSet<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
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
}
