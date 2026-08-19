package com.qingyu.care.dto;

import com.qingyu.care.controller.HandoverRuleController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 扫描式守护「每个 PUT 端点的请求体都必须被登记过」。
 *
 * <p><b>为什么需要这一条</b>：{@link UpdateRequestFieldCoverageTest} 与
 * {@code UpdateRequestNoInitializerTest} 都靠<b>类名后缀</b> {@code UpdateRequest} 去
 * {@code dto} 包里发现目标。那是一条命名约定，而命名约定挡不住「换个名字」——把新 DTO 叫成
 * {@code XxxRequest} 就完全绕开了两道哨兵，比「往白名单里塞条目并编个理由」容易得多，也更
 * 不容易被评审看出来。
 *
 * <p>⚠️ <b>这不是假想的逃生舱，边界外真的藏过一个活缺陷</b>：{@code KnowledgeBaseRequest}
 * （建库、改库共用，{@code enabled} 写了 {@code = true} 初始化器）长期喂着
 * {@code PUT /knowledge/{id}}，判空恒真——改一次知识库名字就把已停用的库重新启用了，无提示
 * 无日志。它躲开两道哨兵的唯一原因就是类名不以 {@code UpdateRequest} 结尾。技术债第 11 项
 * 「八个控制器、九个端点全部改用专用 DTO」那次清理也因此漏了它。
 *
 * <p><b>本类换一个方向发现目标</b>：不看类名，改为扫 {@code controller} 包下所有
 * {@code @PutMapping} 方法的 {@code @RequestBody} 参数类型。凡是项目自有的复合类型
 * （{@code com.qingyu.care} 包下；{@code String}/{@code Map} 这类天然排除），都必须二选一：
 * <ul>
 *   <li>登记进 {@link UpdateRequestFieldCoverageTest#DTO_TO_ENTITY}——即接受「实体字段必须
 *       进 DTO 或进白名单」的约束；</li>
 *   <li>或登记进 {@link #FULL_UPDATE_EXEMPTIONS} 并写明理由——即声明它是<b>全量覆盖</b>
 *       语义，不存在「{@code null} 表示请求体没给」这回事，判空恒真也就无从谈起。</li>
 * </ul>
 *
 * <p>两条发现路径<b>互补，不可互相替代</b>：本类看得见「已接线到端点」的请求体，看不见
 * 「DTO 写好了还没接线」的；{@link UpdateRequestFieldCoverageTest} 正相反。
 *
 * @author ruan
 */
class PutEndpointBodyRegistrationTest {

    private static final String CONTROLLER_PACKAGE = "com.qingyu.care.controller";

    /** 项目自有类型的包前缀。请求体是这个前缀之外的类型（{@code String}/{@code Map} 等）就不是本类的目标。 */
    private static final String OWN_PACKAGE_PREFIX = "com.qingyu.care.";

    /**
     * 全量覆盖语义的 PUT 请求体：请求体每次都带齐全部字段，服务端无条件覆盖，因此不存在
     * 「没传的字段被打回类默认值」这个失效模式。理由必须能回答「为什么这个端点不需要判空语义」。
     */
    private static final Map<Class<?>, String> FULL_UPDATE_EXEMPTIONS = buildExemptions();

    private static Map<Class<?>, String> buildExemptions() {
        Map<Class<?>, String> exemptions = new LinkedHashMap<>();

        exemptions.put(QaPairRequest.class,
                "全量覆盖：question/answer 两个字段都是 @NotBlank 必填，控制器直接把两个值传给 "
                        + "qaPairService.update，没有任何 if (x != null) 分支，故不存在判空恒真的失效模式");

        exemptions.put(CrawlSiteRequest.class,
                "全量覆盖：SiteCrawlService.applyRequest 无条件重设全部字段，前端编辑对话框用 initial "
                        + "预填全部字段后整份提交；未传字段由服务端按 SiteCrawlProperties 钳制成默认值，"
                        + "这是有意的（爬取参数的默认值必须由服务端配置决定，不能由客户端省略与否决定）");

        exemptions.put(HandoverRuleController.UpdateRuleRequest.class,
                "record（定义在 HandoverRuleController 内），组件由构造器赋值、没有字段初始化器这回事，"
                        + "Jackson 缺字段即 null，判空语义天然成立；且它只有 enabled/config 两个字段，"
                        + "服务层 updateRule 逐个判空");

        return exemptions;
    }

    @Test
    @DisplayName("每个 PUT 端点的自有类型请求体：要么登记进 DTO_TO_ENTITY，要么登记进全量覆盖豁免表")
    void 每个PUT端点的请求体都必须被登记过() {
        List<PutBody> bodies = scanPutRequestBodies();

        // 扫描坏掉（包名改了、控制器命名规则变了、Spring 注解换了）时下面的循环会空转，
        // 断言退化成「空==空」永真——这正是本仓库反复栽过的坑，先把「扫到的端点数非空」钉住。
        assertThat(bodies)
                .as("%s 包下一个带 @RequestBody 的 @PutMapping 都没扫到——扫描逻辑本身坏了，"
                        + "下面的登记断言会全部空转", CONTROLLER_PACKAGE)
                .isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (PutBody body : bodies) {
            if (UpdateRequestFieldCoverageTest.DTO_TO_ENTITY.containsKey(body.type())) {
                continue;
            }
            if (FULL_UPDATE_EXEMPTIONS.containsKey(body.type())) {
                continue;
            }
            violations.add(body.describe() + " 的请求体 " + body.type().getSimpleName()
                    + " 既没登记进 UpdateRequestFieldCoverageTest.DTO_TO_ENTITY，"
                    + "也没登记进 PutEndpointBodyRegistrationTest.FULL_UPDATE_EXEMPTIONS。"
                    + "前者要求「实体字段进 DTO 或进白名单」，后者要求说明「本端点是全量覆盖语义」——"
                    + "必须二选一，不能两边都不占");
        }

        assertThat(violations)
                .as("以下 PUT 端点的请求体没有被任何一张表登记过，即它对「局部更新把没传的字段打回"
                        + "类默认值」这类静默缺陷零守护——KnowledgeBaseRequest 当年就是这样漏掉的")
                .isEmpty();
    }

    /** 豁免理由的最小长度，与 {@code UpdateRequestFieldCoverageTest} 同口径。 */
    private static final int MIN_REASON_LENGTH = 8;

    @Test
    @DisplayName("豁免表条目必须仍对应真实存在的 PUT 请求体、且不与 DTO_TO_ENTITY 重复登记")
    void 豁免表不得腐烂成死名单() {
        List<PutBody> bodies = scanPutRequestBodies();

        assertThat(bodies)
                .as("一个 PUT 请求体都没扫到，豁免表核对会全部空转")
                .isNotEmpty();

        List<Class<?>> scannedTypes = bodies.stream().map(PutBody::type).distinct().toList();

        List<String> invalidEntries = new ArrayList<>();
        for (Map.Entry<Class<?>, String> entry : FULL_UPDATE_EXEMPTIONS.entrySet()) {
            Class<?> type = entry.getKey();
            String reason = entry.getValue();

            if (reason == null || reason.isBlank() || reason.trim().length() < MIN_REASON_LENGTH) {
                invalidEntries.add(type.getSimpleName() + " 的豁免理由为空或过短（少于 "
                        + MIN_REASON_LENGTH + " 个字符）——理由是给人读的，不能留空/占位符");
            }
            if (!scannedTypes.contains(type)) {
                invalidEntries.add(type.getSimpleName()
                        + " 已经不是任何 PUT 端点的请求体了（端点删了或换了请求体），该条豁免应删除");
            }
            if (UpdateRequestFieldCoverageTest.DTO_TO_ENTITY.containsKey(type)) {
                invalidEntries.add(type.getSimpleName()
                        + " 同时出现在 DTO_TO_ENTITY 和豁免表里。两张表语义互斥（一个受字段覆盖约束、"
                        + "一个声明为全量覆盖无需约束），重复登记会让人以为它受前者保护、实则被后者放行");
            }
        }

        assertThat(invalidEntries)
                .as("以下豁免条目已名不副实，留着会变成没人敢删的死名单，或掩盖一条其实需要守护的端点")
                .isEmpty();
    }

    /** 一个 PUT 端点的请求体：记下端点位置是为了让失败信息能直接定位到方法。 */
    private record PutBody(Class<?> controller, Method method, Class<?> type) {
        String describe() {
            return controller.getSimpleName() + "." + method.getName() + "()";
        }
    }

    /**
     * 扫 {@link #CONTROLLER_PACKAGE}（含子包）下所有 {@code @PutMapping} 方法带
     * {@code @RequestBody} 的参数，只保留项目自有的复合类型。
     *
     * <p>不用 {@code @RequestMapping(method = PUT)} 的形式做兼容——全仓当前没有这种写法
     * （已核）。真出现时本类会漏掉它，那属于「换个写法绕过」，与本类要防的「换个名字绕过」
     * 同类；届时补一个分支即可。
     */
    private static List<PutBody> scanPutRequestBodies() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*Controller")));

        List<PutBody> bodies = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(CONTROLLER_PACKAGE)) {
            String className = definition.getBeanClassName();
            Class<?> controller;
            try {
                controller = Class.forName(className);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("扫描到的控制器无法加载: " + className, e);
            }

            for (Method method : controller.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(PutMapping.class)) {
                    continue;
                }
                for (Parameter parameter : method.getParameters()) {
                    if (!parameter.isAnnotationPresent(RequestBody.class)) {
                        continue;
                    }
                    Class<?> type = parameter.getType();
                    if (!type.getName().startsWith(OWN_PACKAGE_PREFIX)) {
                        continue;   // String / Map 这类不是「专用 DTO」，没有字段初始化器问题
                    }
                    bodies.add(new PutBody(controller, method, type));
                }
            }
        }
        return bodies;
    }
}
