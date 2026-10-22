package com.qingyu.care.service;

import com.qingyu.care.dto.ChatStreamEvent;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 流式 ```spec 围栏增量切分：逐块喂入 LLM 文本，吐出 DELTA（围栏外文本）与 CARD（围栏闭合后的完整 UI 树）。
 *
 * <p><b>识别规则与 {@link UiSpecService#FENCE} 严格同源</b>：起始标记 ```spec 后跟可选空白 + 换行，
 * 结束标记 ```。两者一旦漂移，会出现「流式渲染成卡片、落库却是文本」的分裂。
 *
 * <p>fail-soft（延续 UiSpecService 铁律）：围栏内 JSON 非法 → 内容当文本吐出；流结束时围栏未闭合
 * → 连同起始标记原样回退为文本；任何情况都不抛异常阻断回复。
 *
 * <p>有状态，非线程安全：每条流用一个新实例（调用方用 Flux.defer 包裹创建）。
 *
 * @author ruan
 */
@Slf4j
public class StreamingSpecSplitter {

    private static final String FENCE_START = "```spec";
    private static final String FENCE_END = "```";

    private final ObjectMapper objectMapper;
    private final StringBuilder buf = new StringBuilder();

    /** 是否处于围栏内（围栏内的内容全部缓冲，不吐 DELTA）。 */
    private boolean inFence = false;
    /** 围栏起始标记的原文（含其后空白与换行）；未闭合时 flush 要原样吐回，保证与 parse 一致。 */
    private String openFenceRaw = "";

    public StreamingSpecSplitter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 喂入一块文本，返回本次可确定的事件（可能为空——数据还不足以决策时会继续缓冲）。 */
    public List<ChatStreamEvent> accept(String chunk) {
        List<ChatStreamEvent> events = new ArrayList<>();
        if (chunk == null || chunk.isEmpty()) {
            return events;
        }
        buf.append(chunk);
        boolean progressed = true;
        while (progressed) {
            progressed = inFence ? scanFenceEnd(events) : scanFenceStart(events);
        }
        return events;
    }

    /** 流结束：吐出全部残余（未闭合围栏连同起始标记原样回退为文本）。 */
    public List<ChatStreamEvent> flush() {
        List<ChatStreamEvent> events = new ArrayList<>();
        String rest = (inFence ? openFenceRaw : "") + buf;
        if (!rest.isEmpty()) {
            events.add(ChatStreamEvent.delta(rest));
        }
        buf.setLength(0);
        inFence = false;
        openFenceRaw = "";
        return events;
    }

    /**
     * 围栏外：找起始标记。返回是否取得进展（取得则继续循环扫描）。
     * 找不到时保留末尾可能被切断的部分标记（最多 FENCE_START.length()-1 个字符）等下一块。
     */
    private boolean scanFenceStart(List<ChatStreamEvent> events) {
        int idx = buf.indexOf(FENCE_START);
        if (idx < 0) {
            int keep = partialMarkerLen(buf, FENCE_START);
            if (buf.length() > keep) {
                emitText(events, buf.substring(0, buf.length() - keep));
                buf.delete(0, buf.length() - keep);
            }
            return false;
        }
        // 起始标记后必须是「可选空白 + 换行」，否则只是普通文本（如 ```specification）
        int scan = idx + FENCE_START.length();
        while (scan < buf.length() && isInlineWhitespace(buf.charAt(scan))) {
            scan++;
        }
        if (scan >= buf.length()) {
            // 还不能判定：先把标记之前的文本吐掉，标记本身留在缓冲等更多数据
            if (idx > 0) {
                emitText(events, buf.substring(0, idx));
                buf.delete(0, idx);
            }
            return false;
        }
        if (buf.charAt(scan) != '\n') {
            // 确定不是围栏：把「文本 + 起始标记」整体当文本吐出，继续找下一个
            emitText(events, buf.substring(0, idx + FENCE_START.length()));
            buf.delete(0, idx + FENCE_START.length());
            return true;
        }
        if (idx > 0) {
            emitText(events, buf.substring(0, idx));
        }
        openFenceRaw = buf.substring(idx, scan + 1);
        buf.delete(0, scan + 1);
        inFence = true;
        return true;
    }

    /** 围栏内：找结束标记。找到则产出 CARD（JSON 非法时 fail-soft 退文本）。 */
    private boolean scanFenceEnd(List<ChatStreamEvent> events) {
        int idx = buf.indexOf(FENCE_END);
        if (idx < 0) {
            return false;   // 围栏内容全部缓冲，等闭合
        }
        String json = buf.substring(0, idx).trim();
        buf.delete(0, idx + FENCE_END.length());
        inFence = false;
        openFenceRaw = "";
        try {
            events.add(ChatStreamEvent.card(objectMapper.readValue(json, Object.class)));
        } catch (Exception e) {
            log.debug("流式卡片围栏 JSON 非法，回退为文本: {}", e.getMessage());
            emitText(events, json);
        }
        return true;
    }

    private void emitText(List<ChatStreamEvent> events, String text) {
        if (!text.isEmpty()) {
            events.add(ChatStreamEvent.delta(text));
        }
    }

    /** 空白但不含换行（换行是围栏起始的终止符，不能被跳过）。 */
    private static boolean isInlineWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\r';
    }

    /**
     * buf 末尾与 marker 前缀的最长匹配长度（最多 marker.length()-1）。
     * 用于「起始标记被 chunk 切断」时保留悬而未决的尾巴，避免把半个标记当文本吐出去。
     */
    static int partialMarkerLen(CharSequence buf, String marker) {
        int max = Math.min(buf.length(), marker.length() - 1);
        for (int len = max; len > 0; len--) {
            boolean match = true;
            for (int i = 0; i < len; i++) {
                if (buf.charAt(buf.length() - len + i) != marker.charAt(i)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return len;
            }
        }
        return 0;
    }
}
