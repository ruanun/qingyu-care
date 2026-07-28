package com.qingyu.care.service;

import com.qingyu.care.config.TenantOwnershipGuard;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.entity.QaPair;
import com.qingyu.care.enums.QaPairSource;
import com.qingyu.care.enums.QaPairStatus;
import com.qingyu.care.exception.InvalidOperationException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.QaPairRepository;
import com.qingyu.care.repository.VectorStoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * QA 问答对服务：CRUD + 向量生命周期（整条「问+答」不切分嵌入共享向量库）。
 * 归属校验：按 id 加载后 belongsToCurrentContext；经 kbId 入口先 KnowledgeService.getKnowledgeBase 校验。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QaPairService {

    private final QaPairRepository qaPairRepository;
    private final KnowledgeService knowledgeService;
    private final VectorStore vectorStore;
    private final VectorStoreRepository vectorStoreRepository;
    private final EmbeddingModelProvider embeddingModelProvider;

    public List<QaPair> list(Long knowledgeBaseId) {
        knowledgeService.getKnowledgeBase(knowledgeBaseId); // 归属校验（越权抛）
        return qaPairRepository.findByKnowledgeBaseId(knowledgeBaseId);
    }

    @Transactional
    public QaPair create(Long knowledgeBaseId, String question, String answer) {
        KnowledgeBase kb = knowledgeService.getKnowledgeBase(knowledgeBaseId);
        QaPair qa = qaPairRepository.save(QaPair.builder()
                .knowledgeBase(kb).question(question).answer(answer)
                .source(QaPairSource.MANUAL).status(QaPairStatus.ACTIVE).build());
        embed(qa);
        return qa;
    }

    /** 按 id 取 QA 对，findById 不受 @Filter 保护，显式补归属校验，越权按「不存在」防探测。 */
    public QaPair getQaPair(Long id) {
        QaPair qa = qaPairRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("问答对", id.toString()));
        if (!TenantOwnershipGuard.belongsToCurrentContext(qa.getTenantId(), qa.getAppId())) {
            throw new ResourceNotFoundException("问答对", id.toString());
        }
        return qa;
    }

    @Transactional
    public QaPair update(Long id, String question, String answer) {
        QaPair qa = getQaPair(id);
        boolean changed = !question.equals(qa.getQuestion()) || !answer.equals(qa.getAnswer());
        qa.setQuestion(question);
        qa.setAnswer(answer);
        qaPairRepository.save(qa);
        // DRAFT 无向量，仅改行；ACTIVE/DISABLED 已有向量，内容变则重嵌
        if (changed && qa.getStatus() != QaPairStatus.DRAFT) {
            vectorStoreRepository.deleteByQaPairId(id.toString());
            embed(qa);
            if (qa.getStatus() == QaPairStatus.DISABLED) {
                vectorStoreRepository.updateDocEnabledByQaPairId(id.toString(), "false");
            }
        }
        return qa;
    }

    /** 复核通过：DRAFT→ACTIVE，嵌入。 */
    @Transactional
    public QaPair approve(Long id) {
        QaPair qa = getQaPair(id);
        if (qa.getStatus() != QaPairStatus.DRAFT) {
            throw new InvalidOperationException("仅草稿状态可复核通过");
        }
        qa.setStatus(QaPairStatus.ACTIVE);
        qaPairRepository.save(qa);
        embed(qa);
        return qa;
    }

    /** 启用/停用：ACTIVE↔DISABLED，翻转向量 docEnabled 标记（不删向量、不重嵌）。 */
    @Transactional
    public QaPair setEnabled(Long id, boolean enabled) {
        QaPair qa = getQaPair(id);
        if (qa.getStatus() == com.qingyu.care.enums.QaPairStatus.DRAFT) {
            throw new InvalidOperationException("草稿需先复核通过");
        }
        qa.setStatus(enabled ? QaPairStatus.ACTIVE : QaPairStatus.DISABLED);
        qaPairRepository.save(qa);
        vectorStoreRepository.updateDocEnabledByQaPairId(id.toString(), String.valueOf(enabled));
        return qa;
    }

    @Transactional
    public void delete(Long id) {
        QaPair qa = getQaPair(id);
        vectorStoreRepository.deleteByQaPairId(id.toString());
        qaPairRepository.delete(qa);
    }

    /** 重嵌一条 QA（删旧向量 + 用当前模型重嵌），供批量重建复用。 */
    public void reembed(QaPair qa) {
        vectorStoreRepository.deleteByQaPairId(qa.getId().toString());
        embed(qa);
    }

    /**
     * 组「问：{q}\n答：{a}」为一条不切分向量入库。metadata 带齐 RetrievalScope 过滤 5 必需键
     * （tenantId/appId/knowledgeBaseId/docEnabled/visibility）+ qaPairId/source/contentType。
     * tenantId/appId 取自 qaPair 行（非 TenantContext），防上下文缺失写空串。package-private 便于同包单测。
     */
    void embed(QaPair qa) {
        String text = "问：" + qa.getQuestion() + "\n答：" + qa.getAnswer();
        Map<String, Object> md = new HashMap<>();
        md.put("qaPairId", qa.getId().toString());
        md.put("knowledgeBaseId", qa.getKnowledgeBase().getId().toString());
        md.put("tenantId", qa.getTenantId() != null ? qa.getTenantId().toString() : "");
        md.put("appId", qa.getAppId() != null ? qa.getAppId().toString() : "");
        md.put("source", truncate(qa.getQuestion(), 100));
        md.put("visibility", "PUBLIC");
        md.put("docEnabled", "true");
        md.put("contentType", "qa");
        md.put("embeddingModel", embeddingModelProvider.getModel());
        vectorStore.add(List.of(Document.builder().text(text).metadata(md).build()));
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
