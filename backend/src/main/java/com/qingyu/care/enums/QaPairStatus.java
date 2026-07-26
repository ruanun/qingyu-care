package com.qingyu.care.enums;

/** QA 问答对状态：DRAFT=生成待复核(无向量) / ACTIVE=已嵌入可检索 / DISABLED=停用(保向量, docEnabled=false) */
public enum QaPairStatus {
    DRAFT, ACTIVE, DISABLED
}
