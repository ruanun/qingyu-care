package com.qingyu.care.enums;

/**
 * A/B 实验状态：DRAFT →(start)→ RUNNING →(end)→ ENDED，不可逆、不可跳步。
 *
 * @author ruan
 */
public enum ExperimentStatus {
    DRAFT, RUNNING, ENDED
}
