package com.qingyu.care.service.tool;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 {@code {key}} 占位符按参数填成最终 URL。纯函数，无状态。
 *
 * <p>从 {@link HttpToolCallback} 抽出来独立成类，因为它不再是三行 replace：path 段与
 * query 段的处理规则**不同**，且这是本项目里最需要密集边界测试的一段——旧实现对 LLM
 * 未填的占位符原样保留，可选 query 参数因此一直是坏的（{@code ?limit={limit}} 被真的
 * 发出去）。
 *
 * <p>两段规则：
 * <ul>
 *   <li><b>path 段</b>：占位符必须全部有值，缺一个就抛异常——path 参数填不出来意味着
 *       这次调用根本不成立，发出去只会拿到 404 而 LLM 无从判断原因；
 *   <li><b>query 段</b>：按 {@code &} 拆开逐段判断，段内任一占位符缺值就<b>整段删除</b>
 *       （{@code range=5-} 这种半填值几乎肯定比不传更糟），无占位符的静态段原样保留。
 * </ul>
 *
 * @author ruan
 */
public final class UrlTemplateFiller {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");

    private UrlTemplateFiller() {
    }

    /**
     * @param template 含 {@code {key}} 占位符的 URL 模板
     * @param args     LLM 交来的参数（可能缺少可选项，也可能含模板里没有的键）
     * @throws IllegalArgumentException path 段有占位符没被填上
     */
    public static String fill(String template, Map<String, Object> args) {
        int q = template.indexOf('?');
        String path = q < 0 ? template : template.substring(0, q);
        String query = q < 0 ? null : template.substring(q + 1);

        String filledPath = replacePresent(path, args, true);
        requireNoPlaceholder(filledPath);

        if (query == null || query.isBlank()) {
            return filledPath;
        }

        List<String> kept = new ArrayList<>();
        for (String segment : query.split("&")) {
            if (segment.isEmpty()) {
                continue;
            }
            String filled = replacePresent(segment, args, false);
            // 段内仍有占位符 = 该参数 LLM 没填 → 整段丢弃。
            // ⚠️ 变异点：改成 kept.add(filled) 会退回旧的坏行为，UrlTemplateFillerTest
            // 的「可选query参数未填时整段删除」会转红。
            if (PLACEHOLDER.matcher(filled).find()) {
                continue;
            }
            kept.add(filled);
        }
        return kept.isEmpty() ? filledPath : filledPath + "?" + String.join("&", kept);
    }

    /**
     * 只替换 args 里**存在**的键，不存在的占位符原样留下——留下的形态正是上面两段规则的判据。
     *
     * <p>遍历模板里的占位符而不是遍历 args：只有这样才能区分「填了」与「没填」。
     */
    private static String replacePresent(String text, Map<String, Object> args, boolean inPath) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            if (!args.containsKey(key)) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }
            String raw = String.valueOf(args.get(key));
            // path 段里填空串、"." 或 ".." 会改变路径结构：/v1/orders/{id} 填 "." 就变成集合端点 /v1/orders/.，
            // 可能把「查一个订单」变成「列出全部订单」。URLEncoder 不编码点号，只能在这里拦（2026-09-30 安全审查）
            if (inPath && (raw.isEmpty() || raw.equals(".") || raw.equals(".."))) {
                throw new IllegalArgumentException("参数 " + key + " 取值非法");
            }
            String encoded = URLEncoder.encode(raw, StandardCharsets.UTF_8);
            // quoteReplacement：replacement 串里的 $ 与 \ 对 appendReplacement 有特殊含义。
            // URLEncoder 已把它们编码掉，此处是纵深防御，别删。
            m.appendReplacement(sb, Matcher.quoteReplacement(encoded));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static void requireNoPlaceholder(String path) {
        Matcher m = PLACEHOLDER.matcher(path);
        if (m.find()) {
            throw new IllegalArgumentException("缺少必填参数 " + m.group(1));
        }
    }
}
