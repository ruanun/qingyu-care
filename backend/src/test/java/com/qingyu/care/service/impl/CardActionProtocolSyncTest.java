package com.qingyu.care.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 守护「卡片 Action URI 协议白名单三处必须一致」这条约定。
 *
 * <p>白名单分散在三个语言里，谁都不认识谁：
 * <ol>
 *   <li>{@link ChatServiceImpl} 的 {@code CARD_PROMPT}（Java 文本，决定 LLM <b>会生成</b>什么）</li>
 *   <li>{@code frontend/src/lib/actionUri.ts} 的 {@code VALID_PROTOCOLS}（管理端<b>认</b>什么）</li>
 *   <li>{@code widget/src/core/actionUri.ts} 的 {@code VALID_PROTOCOLS}（widget <b>认</b>什么）</li>
 * </ol>
 *
 * <p>漏改任一处都<b>不会报错</b>，只会让按钮悄悄失效：prompt 里多出一个客户端不认的协议，
 * 点击时 {@code executeActionUri} 返回 false，按钮退化成把 URI 原文当消息发出去；
 * 反过来客户端多认一个 LLM 从不生成的协议，则是永远走不到的死代码。两个方向都没有任何现象提醒。
 *
 * <p>本测试不检查「这三个协议实现得对不对」（那是各端自己的单测），而是<b>只比对白名单集合</b>：
 * 三处必须逐字一致，改一处忘了另两处就红。这是 CLAUDE.md 里长期标注「没有任何自动化测试能比对它们」
 * 的那条缺口——现在补上了。
 *
 * @author ruan
 */
class CardActionProtocolSyncTest {

    /**
     * 从 {@code CARD_PROMPT} 抽协议时要排掉的通用 URL scheme。
     *
     * <p>prompt 里有 {@code url://https://shop.example.com/orders/10086} 这类示例，
     * 其中 {@code https} 是<b>被包在里面的普通链接</b>、不是动作协议。抽取正则已用负向后顾排掉
     * 紧跟在 {@code /} 或 {@code :} 后面的那种，这里再兜一层：凡是散文里裸写的通用 scheme
     * （如「参考 https://docs...」）也不算动作协议。
     *
     * <p>代价是动作协议<b>不能</b>起这些名字。真要新增一个叫 {@code file} 的动作协议，
     * 得先把它从这里删掉——那一步会逼作者意识到命名撞车。
     */
    private static final Set<String> NON_ACTION_SCHEMES = Set.of(
            "http", "https", "ftp", "ftps", "ws", "wss",
            "data", "blob", "file", "mailto", "tel"
    );

    /** 匹配 {@code xxx://}，但前面不能是单词字符 / {@code /} / {@code :}——排掉嵌在 URL 里的那截。 */
    private static final Pattern PROTOCOL_IN_PROMPT = Pattern.compile("(?<![\\w/:])([a-z]+)://");

    /** 匹配 {@code const VALID_PROTOCOLS = ['url', 'msg', 'copy'];} 的数组体。 */
    private static final Pattern VALID_PROTOCOLS_ARRAY =
            Pattern.compile("VALID_PROTOCOLS\\s*(?::[^=]*)?=\\s*\\[([^\\]]*)]");

    /** 从数组体里抽单引号/双引号包裹的字面量。 */
    private static final Pattern QUOTED_LITERAL = Pattern.compile("['\"]([^'\"]+)['\"]");

    private static final String FRONTEND_ACTION_URI = "frontend/src/lib/actionUri.ts";
    private static final String WIDGET_ACTION_URI = "widget/src/core/actionUri.ts";

    @Test
    @DisplayName("卡片 Action URI 协议白名单：CARD_PROMPT、frontend、widget 三处完全一致")
    void card_action_protocols_are_in_sync_across_three_languages() {
        Path repoRoot = locateRepoRoot();

        Set<String> fromPrompt = protocolsDeclaredInCardPrompt();
        Set<String> fromFrontend = validProtocolsIn(repoRoot.resolve(FRONTEND_ACTION_URI));
        Set<String> fromWidget = validProtocolsIn(repoRoot.resolve(WIDGET_ACTION_URI));

        // 先各自非空——抽取正则一旦被上游改动失配，集合会静默变空，
        // 那时「三处相等」会退化成「空 == 空 == 空」这条永真断言。
        assertThat(fromPrompt)
                .as("从 ChatServiceImpl.CARD_PROMPT 抽出的协议为空——多半是 prompt 里协议写法变了，"
                        + "抽取正则 %s 已失配，本测试正在空转", PROTOCOL_IN_PROMPT.pattern())
                .isNotEmpty();
        assertThat(fromFrontend)
                .as("从 %s 抽出的 VALID_PROTOCOLS 为空——多半是声明写法变了，抽取正则已失配", FRONTEND_ACTION_URI)
                .isNotEmpty();
        assertThat(fromWidget)
                .as("从 %s 抽出的 VALID_PROTOCOLS 为空——多半是声明写法变了，抽取正则已失配", WIDGET_ACTION_URI)
                .isNotEmpty();

        assertThat(fromFrontend)
                .as("两端客户端白名单不一致：%s=%s，%s=%s。"
                        + "两份是同一套规则的两份实现（widget 不依赖 React 无法共享），改一处必须同步另一处",
                        FRONTEND_ACTION_URI, fromFrontend, WIDGET_ACTION_URI, fromWidget)
                .containsExactlyInAnyOrderElementsOf(fromWidget);

        assertThat(fromPrompt)
                .as("CARD_PROMPT 声明的协议与客户端白名单不一致：prompt=%s，客户端=%s。"
                        + "prompt 多出来的协议 → LLM 会生成、客户端不认，按钮退化成发一条 URI 原文；"
                        + "客户端多出来的协议 → LLM 从不生成，是走不到的死代码。两者都没有任何现象提醒",
                        fromPrompt, fromFrontend)
                .containsExactlyInAnyOrderElementsOf(fromFrontend);
    }

    /** 反射读 {@code CARD_PROMPT} 真值——读源码文本会把注释里的示例也算进去。 */
    private static Set<String> protocolsDeclaredInCardPrompt() {
        String prompt;
        try {
            Field field = ChatServiceImpl.class.getDeclaredField("CARD_PROMPT");
            field.setAccessible(true);
            prompt = (String) field.get(null);
        } catch (NoSuchFieldException e) {
            return fail("ChatServiceImpl.CARD_PROMPT 字段不存在——卡片提示词被改名或删除了，"
                    + "本测试守护的三处同步约定需要同步更新", e);
        } catch (IllegalAccessException e) {
            return fail("无法读取 ChatServiceImpl.CARD_PROMPT", e);
        }

        Set<String> protocols = new LinkedHashSet<>();
        Matcher m = PROTOCOL_IN_PROMPT.matcher(prompt);
        while (m.find()) {
            String protocol = m.group(1).toLowerCase();
            if (!NON_ACTION_SCHEMES.contains(protocol)) {
                protocols.add(protocol);
            }
        }
        return protocols;
    }

    private static Set<String> validProtocolsIn(Path actionUriFile) {
        // 刻意不 skip：文件找不到时静默跳过会让这条守护退化成永真，
        // 而「白名单漂移」恰恰是没有任何其它信号的那类缺陷。
        if (!Files.isRegularFile(actionUriFile)) {
            return fail("找不到 %s——本测试需要在完整仓库（含 frontend/ 与 widget/）下运行",
                    actionUriFile);
        }

        String source;
        try {
            source = Files.readString(actionUriFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("读取 %s 失败".formatted(actionUriFile), e);
        }

        Matcher arrayMatcher = VALID_PROTOCOLS_ARRAY.matcher(source);
        if (!arrayMatcher.find()) {
            return fail("%s 里找不到 VALID_PROTOCOLS 数组声明——声明写法变了的话，"
                    + "本测试的抽取正则 %s 也要跟着改", actionUriFile, VALID_PROTOCOLS_ARRAY.pattern());
        }

        Set<String> protocols = new LinkedHashSet<>();
        Matcher literalMatcher = QUOTED_LITERAL.matcher(arrayMatcher.group(1));
        while (literalMatcher.find()) {
            protocols.add(literalMatcher.group(1).toLowerCase());
        }
        return protocols;
    }

    /**
     * 从当前工作目录向上找仓库根。
     *
     * <p>不写死相对路径的原因：{@code mvn test} 在 backend/ 下跑、
     * {@code mvn -f backend/pom.xml test} 在仓库根跑，两种都得能过。
     */
    private static Path locateRepoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve(FRONTEND_ACTION_URI))
                    && Files.isRegularFile(dir.resolve(WIDGET_ACTION_URI))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return fail("从 %s 向上找不到同时含 %s 与 %s 的仓库根",
                Paths.get("").toAbsolutePath(), FRONTEND_ACTION_URI, WIDGET_ACTION_URI);
    }
}
