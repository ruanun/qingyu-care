package com.qingyu.care.repository.projection;

import java.time.LocalDateTime;

/**
 * 未命中提问明细行。
 *
 * @author ruan
 */
public interface MissedQuestionRow {
    String getContent();
    LocalDateTime getCreatedAt();
    String getSessionId();
}
