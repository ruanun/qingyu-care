package com.qingyu.care.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 简化的分页响应 DTO
 * 用于替代 Spring 的 Page 对象，避免 Jackson 序列化问题
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageDTO<T> {
    
    /**
     * 当前页内容
     */
    private List<T> content;
    
    /**
     * 总元素数
     */
    private long totalElements;
    
    /**
     * 总页数
     */
    private int totalPages;
    
    /**
     * 当前页码（从0开始）
     */
    private int number;
    
    /**
     * 每页大小
     */
    private int size;
    
    /**
     * 是否为第一页
     */
    private boolean first;
    
    /**
     * 是否为最后一页
     */
    private boolean last;
    
    /**
     * 从 Spring Page 对象转换
     */
    public static <T> PageDTO<T> from(Page<T> page) {
        return PageDTO.<T>builder()
                .content(page.getContent())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .number(page.getNumber())
                .size(page.getSize())
                .first(page.isFirst())
                .last(page.isLast())
                .build();
    }
}
