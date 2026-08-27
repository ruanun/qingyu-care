package com.qingyu.care.controller;

import com.qingyu.care.util.SecurityUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 扫描式守护「{@code /v1/platform/**} 下的每个端点都必须调用
 * {@link SecurityUtil#requirePlatformAdmin()}」。
 *
 * <p><b>这条约定为什么需要机器强制。</b>本仓库没有声明式的角色注解（没有 Sa-Token 的
 * {@code @SaCheckRole}、也没有 Spring Security 的 {@code @PreAuthorize}），平台端点的授权
 * 完全靠每个方法体第一行手写一句 {@code SecurityUtil.requirePlatformAdmin();}。漏写的后果
 * 是<b>任意登录用户</b>（含任意租户的普通成员）能读写平台共享资源——共享工具与共享模型的
 * 明文凭据就在响应体里（{@code listSharedTools()} 刻意不脱敏，见 {@code ChatToolService}
 * 那条注释），而端点照常返回 200，没有任何现象提醒。
 *
 * <p><b>补它的时机是新增 {@code PlatformPackageController} 那一刻</b>：写这条哨兵时全仓 12 个
 * 平台端点里 11 个守卫齐备、1 个是刻意例外（见 {@link #ALLOWLIST}），即<b>没有存量漏洞可修</b>，
 * 它守的完全是「以后新增平台端点时忘了写那一行」。
 *
 * <p><b>为什么是「反射拿路径 + 源码找方法体」的混合方案。</b>类级路径用反射取最稳
 * （{@code @RequestMapping} 的 value 可能写成常量或用 {@code value =} 具名形式，源码正则都会
 * 漏）；「方法体里有没有调那一句」则只能扫源码——字节码方案（ASM）更精确但要引依赖。范式与
 * {@code AsyncDispatchOutsideTransactionTest} 同源（那条也是反射拿 {@code @Async} 方法名 +
 * 源码扫方法体），连同它的两条防空转断言一起抄过来。
 *
 * <p>⚠️ <b>已知盲区：只认「方法体里直接出现那一句」</b>。把守卫藏进私有辅助方法再调用，
 * 本哨兵会漏掉——同 {@code AsyncDispatchOutsideTransactionTest} 对间接投递的盲区，
 * 覆盖它要做调用图分析，代价大于收益。
 *
 * <p>⚠️ <b>本哨兵只管「有没有调用守卫」，不管「守卫对不对」</b>：一个端点即使调了
 * {@code requirePlatformAdmin()}，仍可能在后续逻辑里用别人的 id 越权操作（那类问题由
 * {@code PlatformController.updateSharedTool} 里的 {@code scope != PLATFORM} 二次校验挡，
 * 见那些方法的注释）。两者是正交的两层，别因为有了本哨兵就删掉那些校验。
 *
 * @author ruan
 */
class PlatformEndpointGuardTest {

    private static final String CONTROLLER_PACKAGE = "com.qingyu.care.controller";

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    /** 受本哨兵管辖的路径前缀。 */
    private static final String PLATFORM_PREFIX = "/v1/platform";

    /** 要求出现在方法体里的那一句（只匹配调用本身，允许 {@code SecurityUtil.} 前缀在与不在）。 */
    private static final Pattern GUARD_CALL =
            Pattern.compile("requirePlatformAdmin\\s*\\(\\s*\\)");

    /**
     * 刻意不要求平台管理员的例外，格式 {@code 类简单名#方法名}，值是理由。
     *
     * <p>往里加条目前先确认：这个端点返回的东西<b>对任意登录用户都是安全的</b>。平台端点的
     * 默认答案是「不安全」——共享工具/模型的响应体里带着明文凭据。
     */
    private static final Map<String, String> ALLOWLIST = Map.of(
            "PlatformConfigController#announcement",
            "系统公告，任意登录用户可读（管理后台顶部横幅要用，见 MainLayout.tsx 的 getAnnouncement）。"
                    + "它自己调 StpUtil.checkLogin() 要求登录，且只返回单个配置项 platform.announcement 的值，"
                    + "不经过任何带凭据的实体");

    @Test
    @DisplayName("/v1/platform/** 下每个端点方法都调用了 SecurityUtil.requirePlatformAdmin()")
    void 平台端点必须显式要求平台管理员() {
        List<Endpoint> endpoints = scanPlatformEndpoints();

        // 扫描坏掉（包名改了、路径前缀变了、注解换了）时下面的循环会空转、断言退化成
        // 「空==空」永真——本仓库反复栽过这个坑，先把「扫到的端点数非空」钉成一条断言。
        assertThat(endpoints)
                .as("%s 包下一个 %s 端点都没扫到——扫描逻辑本身坏了，下面的守卫断言会全部空转",
                        CONTROLLER_PACKAGE, PLATFORM_PREFIX)
                .isNotEmpty();

        List<String> violations = new ArrayList<>();
        Set<String> usedAllowlist = new LinkedHashSet<>();

        for (Endpoint endpoint : endpoints) {
            boolean guarded = GUARD_CALL.matcher(endpoint.body()).find();
            if (guarded) {
                if (ALLOWLIST.containsKey(endpoint.id())) {
                    violations.add(endpoint.id() + " 在例外名单里，但它其实调用了 requirePlatformAdmin()"
                            + "——该条例外已名不副实，请删除，否则它会掩护一个同名的新端点");
                }
                continue;
            }
            if (ALLOWLIST.containsKey(endpoint.id())) {
                usedAllowlist.add(endpoint.id());
                continue;
            }
            violations.add(endpoint.id() + " 是 " + PLATFORM_PREFIX
                    + " 下的端点却没有调用 SecurityUtil.requirePlatformAdmin()："
                    + "本仓库没有声明式角色注解，漏掉这一句 = 任意登录用户可读写平台资源"
                    + "（共享工具与模型的响应体里带明文凭据），且端点照常返回 200 没有任何现象。"
                    + "确属例外请加进 ALLOWLIST 并写明为什么它对任意登录用户安全");
        }

        assertThat(violations)
                .as("平台端点的授权守卫有缺口或例外名单已过时")
                .isEmpty();
        assertThat(usedAllowlist)
                .as("例外名单里有条目已不再命中（端点被删/改名/加上了守卫），请同步清理——"
                        + "留着它会掩护一个同名的新违规")
                .containsExactlyInAnyOrderElementsOf(ALLOWLIST.keySet());
    }

    /** 一个平台端点：id 用于白名单寻址，body 是它在源码里的方法体全文。 */
    private record Endpoint(String id, String body) {
    }

    /**
     * 反射发现 {@code /v1/platform} 下的控制器与端点方法，再去源码里取各自的方法体。
     */
    private static List<Endpoint> scanPlatformEndpoints() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*Controller")));

        List<Endpoint> endpoints = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(CONTROLLER_PACKAGE)) {
            String className = definition.getBeanClassName();
            Class<?> controller;
            try {
                controller = Class.forName(className);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("扫描到的控制器无法加载: " + className, e);
            }

            RequestMapping classMapping =
                    AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            if (classMapping == null || !hasPlatformPath(classMapping)) {
                continue;
            }

            List<String> lines = readLines(sourceFileOf(controller));
            for (Method method : controller.getDeclaredMethods()) {
                // @GetMapping/@PostMapping/... 都以 @RequestMapping 为元注解，findMergedAnnotation
                // 一次覆盖全部五种写法，不必逐个枚举注解类型。
                if (AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class) == null) {
                    continue;
                }
                String id = controller.getSimpleName() + "#" + method.getName();
                endpoints.add(new Endpoint(id, methodBody(lines, method.getName(), id)));
            }
        }
        return endpoints;
    }

    private static boolean hasPlatformPath(RequestMapping mapping) {
        for (String path : mapping.value()) {
            if (path.startsWith(PLATFORM_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    private static Path sourceFileOf(Class<?> controller) {
        Path file = SOURCE_ROOT.resolve(controller.getName().replace('.', '/') + ".java");
        assertThat(file)
                .as("找不到 %s 的源文件（测试的工作目录不是模块根目录？）——这时方法体会全是空串，"
                        + "守卫断言会全部误判为违规", controller.getSimpleName())
                .exists();
        return file;
    }

    /**
     * 取源码里该方法的方法体全文（含签名行）。同名重载全部拼接——两个重载都要有守卫才算过，
     * 这是保守的方向。
     */
    private static String methodBody(List<String> lines, String methodName, String id) {
        Pattern signature = Pattern.compile("\\b" + Pattern.quote(methodName) + "\\s*\\(");
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!signature.matcher(line).find() || !line.stripTrailing().endsWith("{")) {
                continue;
            }
            int end = findBlockEnd(lines, i);
            body.append(String.join("\n", lines.subList(i, end + 1))).append('\n');
            i = end;
        }
        assertThat(body.length())
                .as("在源码里没找到 %s 的方法体——签名可能换行了（本方法只认「方法名(... 且行尾是 {」"
                        + "这一种形状），此时守卫断言会误判为违规", id)
                .isPositive();
        return body.toString();
    }

    /** 方法体收尾行：从签名行起做大括号配对（同 AsyncDispatchOutsideTransactionTest）。 */
    private static int findBlockEnd(List<String> lines, int signature) {
        int depth = 0;
        boolean started = false;
        for (int j = signature; j < lines.size(); j++) {
            for (char c : lines.get(j).toCharArray()) {
                if (c == '{') {
                    depth++;
                    started = true;
                } else if (c == '}') {
                    depth--;
                    if (started && depth == 0) {
                        return j;
                    }
                }
            }
        }
        return lines.size() - 1;
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException("读不了源文件: " + file, e);
        }
    }
}
