package com.qingyu.care.dto;

import com.qingyu.care.enums.DocumentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档处理状态 DTO（轻量级响应）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStatusDTO {
    
    /**
     * 文档ID
     */
    private Long id;
    
    /**
     * 文档名称
     */
    private String name;
    
    /**
     * 处理状态
     */
    private DocumentStatus status;
    
    /**
     * 切片数量（处理完成后有值）
     */
    private Integer chunkCount;
    
    /**
     * 错误信息（处理失败时有值）
     */
    private String errorMessage;
    
    /**
     * 处理进度百分比 (0-100)
     */
    private Integer progress;
}
