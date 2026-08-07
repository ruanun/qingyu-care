package com.qingyu.care.repository;

import com.qingyu.care.entity.QaPair;
import com.qingyu.care.enums.QaPairStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface QaPairRepository extends JpaRepository<QaPair, Long> {

    /** 某知识库下的全部 QA 对（kbId 归属由调用方先校验） */
    List<QaPair> findByKnowledgeBaseId(Long knowledgeBaseId);

    /** 删除知识库前的占用检查（见 {@code KnowledgeServiceImpl#deleteKnowledgeBase}）。 */
    long countByKnowledgeBaseId(Long knowledgeBaseId);

    /** 某知识库下按状态过滤（如复核 DRAFT 列表） */
    List<QaPair> findByKnowledgeBaseIdAndStatus(Long knowledgeBaseId, QaPairStatus status);
}
