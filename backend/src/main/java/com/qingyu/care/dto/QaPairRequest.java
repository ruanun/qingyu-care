package com.qingyu.care.dto;

import lombok.Data;

/** QA 问答对创建/更新请求 */
@Data
public class QaPairRequest {
    private String question;
    private String answer;
}
