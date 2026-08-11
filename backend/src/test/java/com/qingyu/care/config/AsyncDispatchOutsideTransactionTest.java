package com.qingyu.care.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.scheduling.annotation.Async;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护「不要在 {@code @Transactional} 方法内部直接投递 {@code @Async} 任务」。
 *
 * <p><b>这条约定为什么存在。</b>{@code @Async} 任务跑在另一个线程、走独立连接。在事务提交前
 * 投递，异步线程可能先被调度，READ COMMITTED 下看不到本事务尚未提交的 INSERT，于是
 * 「按 id 回读刚建的那行」落空。本仓库为它付过三次代价：
 *
 * <ul>
 *   <li>{@code SiteCrawlService.startCrawl}（站点爬取 C-1）：首次「新建站点并开爬」
 *       非确定性地立刻 FAILED「站点不存在」。修法是去掉 {@code @Transactional}。</li>
 *   <li>{@code KnowledgeServiceImpl.uploadDocument}：{@code processDocument} 取不到行时是
 *       {@code orElse(null)} 后直接 return，<b>完全静默</b>——上传的文件永远停在 PENDING、
 *       不入向量库、无日志无异常。</li>
 *   <li>{@code WebPageSyncService.addWebPage}：同上，只多留一句 warn。</li>
 * </ul>
 *
 * <p>前者是人工排查发现的，后两者是<b>写这条扫描测试时才发现的</b>——原来只有一条反射断言
 * 盯着 {@code startCrawl} 一个方法（见 {@code SiteCrawlServiceTest} 里那条），
 * 守不住同一类错误在别处复发。这就是把「守一个方法」换成「守一类错误」的理由。
 *
 * <p><b>为什么扫源码而不是字节码。</b>要判断的是「某个 {@code @Transactional} 方法体内有没有
 * 出现对 {@code @Async} 方法的调用」，字节码方案（ASM）更精确但要引依赖、读起来也重；
 * 源码扫描配合下面两条非空断言足够可靠，且下一个维护者一眼能看懂它在查什么。
 *
 * <p>⚠️ 正确写法：用 {@link com.qingyu.care.util.AfterCommit#run(Runnable)} 把投递推迟到
 * 提交之后；或像 {@code startCrawl} 那样让投递方法不带 {@code @Transactional}；
 * 或像 {@code WebhookDispatcher} 那样用 {@code @TransactionalEventListener(AFTER_COMMIT)}。
 *
 * @author ruan
 */
class AsyncDispatchOutsideTransactionTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    /**
     * 例外白名单，格式 {@code 相对源码路径#行号}（行号指方法签名所在行）。
     *
     * <p>目前为空，且<b>希望它一直是空的</b>。往里加条目之前请先确认：那个异步任务真的不读
     * 本事务写入的任何行（比如审计日志、用量计量这类只往里插、不回读的）。若只是「跑起来
     * 没出问题」，那正是本测试要拦的东西——这条竞态平时不发作，异步线程通常晚于提交被调度，
     * 它只在特定调度时序下现形，而现形时往往是静默的。
     */
    private static final Set<String> ALLOWLIST = Set.of();

    /** {@code @Transactional} 而非 {@code @TransactionalEventListener}——后者本就在提交后跑。 */
    private static final Pattern TRANSACTIONAL = Pattern.compile("^\\s*@Transactional\\b(?!EventListener)");

    @Test
    @DisplayName("没有 @Transactional 方法在事务内直接投递 @Async 任务（异步线程看不到未提交的行）")
    void no_transactional_method_dispatches_async_directly() {
        Set<String> asyncMethodNames = scanAsyncMethodNames();
        List<String> violations = new ArrayList<>();
        Set<String> usedAllowlist = new LinkedHashSet<>();

        for (Path file : javaSources()) {
            List<String> lines = readLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (!TRANSACTIONAL.matcher(lines.get(i)).find()) {
                    continue;
                }
                int signature = findMethodSignature(lines, i);
                if (signature < 0) {
                    continue;
                }
                int end = findBlockEnd(lines, signature);
                // 抠掉 AfterCommit.run(...) 的整段实参——包在它里面的投递是**正确写法**，
                // 不该被算成违规。抠掉而不是"整行忽略"，是为了让多行写法也成立。
                String body = stripAfterCommitBlocks(String.join("\n", lines.subList(signature, end + 1)));
                String id = SOURCE_ROOT.relativize(file).toString().replace('\\', '/')
                        + "#" + (signature + 1);

                for (String async : asyncMethodNames) {
                    if (!Pattern.compile("[.]\\s*" + Pattern.quote(async) + "\\s*[(]").matcher(body).find()) {
                        continue;
                    }
                    if (ALLOWLIST.contains(id)) {
                        usedAllowlist.add(id);
                    } else {
                        violations.add(id + " 在事务内投递了 @Async 方法 " + async
                                + "()；请改用 AfterCommit.run(...) 推迟到提交之后，"
                                + "或让该方法不带 @Transactional（同 SiteCrawlService.startCrawl）");
                    }
                }
                i = end;
            }
        }

        assertThat(violations).as("存在「事务内投递 @Async」的写法，异步线程可能读不到未提交的行").isEmpty();
        assertThat(usedAllowlist)
                .as("白名单里有条目已不再命中（方法被删/改名/行号变了），请同步清理——"
                        + "留着它会掩护一个同位置的新违规")
                .containsExactlyInAnyOrderElementsOf(ALLOWLIST);
    }

    /**
     * 把 {@code AfterCommit.run( ... )} 连同它的实参整段删掉（按括号配对，支持跨行）。
     *
     * <p>删掉之后，方法体里剩下的对 {@code @Async} 方法的调用才是「真的在事务内直接投递」。
     */
    private static String stripAfterCommitBlocks(String body) {
        final String marker = "AfterCommit.run(";
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        while (true) {
            int start = body.indexOf(marker, cursor);
            if (start < 0) {
                out.append(body, cursor, body.length());
                return out.toString();
            }
            out.append(body, cursor, start);
            int depth = 0;
            int k = start + marker.length() - 1; // 停在那个 '('
            for (; k < body.length(); k++) {
                char c = body.charAt(k);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
            }
            cursor = Math.min(k + 1, body.length());
        }
    }

    /** 方法签名行：从注解行往下找第一行以 {@code {} 结尾的（跳过其余注解与换行的参数列表）。 */
    private static int findMethodSignature(List<String> lines, int annotationLine) {
        for (int k = annotationLine + 1; k < Math.min(lines.size(), annotationLine + 12); k++) {
            if (lines.get(k).stripTrailing().endsWith("{")) {
                return k;
            }
        }
        return -1;
    }

    /** 方法体收尾行：从签名行起做大括号配对。 */
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

    private static List<Path> javaSources() {
        assertThat(SOURCE_ROOT)
                .as("源码目录没找到（测试的工作目录不是模块根目录？）——这时下面的断言会全部空过")
                .exists();
        try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
            List<Path> files = paths.filter(p -> p.toString().endsWith(".java")).toList();
            assertThat(files).as("一个 .java 源文件都没扫到，扫描逻辑本身坏了").isNotEmpty();
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException("读不了源文件: " + file, e);
        }
    }

    /** 用反射拿 {@code @Async} 方法名（比从源码里认注解稳），同 {@code AsyncExecutorRoutingTest}。 */
    private static Set<String> scanAsyncMethodNames() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(true);
        Set<String> names = new LinkedHashSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.qingyu.care")) {
            Class<?> type;
            try {
                type = Class.forName(definition.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("扫描到的类无法加载: " + definition.getBeanClassName(), e);
            }
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Async.class)) {
                    names.add(method.getName());
                }
            }
        }
        assertThat(names)
                .as("一个 @Async 方法都没扫到，说明扫描逻辑坏了（这时上面的断言会全部空过）")
                .isNotEmpty();
        return names;
    }
}
