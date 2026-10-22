package com.qingyu.care.dto;

import java.time.LocalDateTime;

/**
 * 未命中提问明细项（知识缺口清单）。
 *
 * @author ruan
 */
public record MissedQuestionDTO(String content, LocalDateTime createdAt, String sessionId) {
}
