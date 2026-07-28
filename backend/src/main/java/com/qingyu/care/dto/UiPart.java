package com.qingyu.care.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 一条 AI 回复的有序片段：纯文本（kind=text）或结构化 UI 节点（kind=ui，node 为解析后的 JSON 树）。 */
@Data
@AllArgsConstructor
public class UiPart {
    private String kind;   // "text" | "ui"
    private String text;   // kind=text 时有值
    private Object node;   // kind=ui 时为解析后的 JSON UI 树（Map/List）

    public static UiPart text(String text) {
        return new UiPart("text", text, null);
    }

    public static UiPart ui(Object node) {
        return new UiPart("ui", null, node);
    }
}
