package com.qingyu.care.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识库创建/更新请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBaseRequest {
    
    @NotBlank(message = "知识库名称不能为空")
    private String name;
    
    private String description;
    
    private Boolean enabled = true;
}
