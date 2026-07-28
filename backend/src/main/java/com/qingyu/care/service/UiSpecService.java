package com.qingyu.care.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.qingyu.care.dto.UiPart;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 AI 回复里内联的 ```spec 围栏为有序 uiParts。fail-soft：非法/未闭合围栏并入文本、绝不抛异常阻断回复。
 * 前端只渲染 uiParts、不含解析器（干净分层）。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UiSpecService {

    private final ObjectMapper objectMapper;

    /** 闭合的 ```spec ... ``` 围栏；DOTALL 让 . 匹配换行，非贪婪取最近的闭合。 */
    private static final Pattern FENCE = Pattern.compile("```spec\\s*\\r?\\n(.*?)```", Pattern.DOTALL);

    public record ParseResult(List<UiPart> uiParts, String plainText) {}

    public ParseResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            String s = raw == null ? "" : raw;
            return new ParseResult(List.of(UiPart.text(s)), s);
        }
        List<UiPart> parts = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        Matcher m = FENCE.matcher(raw);
        int last = 0;
        while (m.find()) {
            String before = raw.substring(last, m.start());
            if (!before.isBlank()) { parts.add(UiPart.text(before)); plain.append(before); }
            String json = m.group(1).trim();
            try {
                Object node = objectMapper.readValue(json, Object.class);
                parts.add(UiPart.ui(node));
            } catch (Exception e) {
                // fail-soft：非法 JSON 的围栏内容并入文本，不抛
                log.debug("卡片围栏 JSON 非法，回退为文本: {}", e.getMessage());
                parts.add(UiPart.text(json)); plain.append(json);
            }
            last = m.end();
        }
        String after = raw.substring(last);   // 含未闭合围栏残余 → 当文本，fail-soft
        if (!after.isBlank()) { parts.add(UiPart.text(after)); plain.append(after); }
        if (parts.isEmpty()) { parts.add(UiPart.text(raw)); plain.append(raw); }
        return new ParseResult(parts, plain.toString().trim());
    }
}
