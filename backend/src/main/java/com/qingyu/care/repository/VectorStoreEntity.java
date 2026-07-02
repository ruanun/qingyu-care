package com.qingyu.care.repository;

import jakarta.persistence.*;
import lombok.Data;

import java.util.UUID;

/**
 * 向量存储实体（映射 Spring AI 的 vector_store 表）
 * 仅用于删除操作，不用于插入
 */
@Data
@Entity
@Table(name = "vector_store")
public class VectorStoreEntity {
    
    @Id
    private UUID id;
    
    @Column(columnDefinition = "TEXT")
    private String content;
    
    @Column(columnDefinition = "json")
    private String metadata;
    
    // embedding 列不需要映射，JPA 不直接支持 vector 类型
}
