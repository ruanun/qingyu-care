package com.qingyu.care.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** QA 问答对创建/更新请求 */
@Data
public class QaPairRequest {
    @NotBlank(message = "问题不能为空")
    private String question;
    @NotBlank(message = "答案不能为空")
    private String answer;
}
