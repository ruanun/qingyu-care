package com.qingyu.care.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.entity.Document;
import com.qingyu.care.entity.QaPair;
import com.qingyu.care.enums.QaPairSource;
import com.qingyu.care.enums.QaPairStatus;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.QaPairRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.qingyu.care.config.AsyncConfig;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * LLM 文档→QA 生成：读文档内容，调 LLM 生成问答对，落为 DRAFT 草稿供人工复核。
 * @Async 线程无 TenantContext，QaPair 的 tenant/app 显式从源文档行回填（沿用 processDocument 教训）。
 * 不计量（内部管理动作，非开放 API）。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QaGenerationService {

    private final DocumentRepository documentRepository;
    private final QaPairRepository qaPairRepository;
    private final ModelRouterService modelRouterService;
    private final ObjectMapper objectMapper;

    /** 从文档内容异步生成 QA 草稿。调用方（控制器）须先在请求线程校验文档归属。 */
    @Async(AsyncConfig.LONG_TASK_EXECUTOR)
    @Transactional
    public void generateFromDocument(Long documentId) {
        try {
            Document doc = documentRepository.findById(documentId).orElse(null);
            if (doc == null || doc.getContent() == null || doc.getContent().isBlank()) {
                log.warn("文档 {} 无内容，跳过 QA 生成", documentId);
                return;
            }
            String prompt = "你是知识库助手。请从下面的资料中提炼尽量多的高质量常见问答对，"
                    + "问题简洁、答案严格基于资料原文、不要编造。只输出 JSON 数组，形如 "
                    + "[{\"question\":\"...\",\"answer\":\"...\"}]，不要输出任何额外文字。\n\n资料：\n" + doc.getContent();
            String resp = modelRouterService.callWithFallback("default",
                    client -> client.prompt().user(prompt).call().content());
            List<QaDraft> drafts = parseQaJson(resp, objectMapper);
            for (QaDraft d : drafts) {
                QaPair qa = QaPair.builder()
                        .knowledgeBase(doc.getKnowledgeBase())
                        .question(d.question()).answer(d.answer())
                        .source(QaPairSource.GENERATED).status(QaPairStatus.DRAFT)
                        .sourceDocumentId(documentId).build();
                // @Async 无 TenantContext：显式从源文档行回填租户/应用，避免 @PrePersist 写 null
                qa.setTenantId(doc.getTenantId());
                qa.setAppId(doc.getAppId());
                qaPairRepository.save(qa);
            }
            log.info("文档 {} 生成 {} 条 QA 草稿", documentId, drafts.size());
        } catch (Exception e) {
            log.error("文档 {} 生成 QA 失败: {}", documentId, e.getMessage(), e);
        }
    }

    /**
     * 容错解析 LLM 返回的 QA JSON 数组：截取首个 '[' 到最后一个 ']'，解析失败/空白项过滤。
     * static 便于纯单测。
     */
    static List<QaDraft> parseQaJson(String raw, ObjectMapper mapper) {
        if (raw == null) {
            return List.of();
        }
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }
        try {
            List<QaDraft> parsed = mapper.readValue(raw.substring(start, end + 1),
                    new TypeReference<List<QaDraft>>() {});
            return parsed.stream()
                    .filter(d -> d.question() != null && !d.question().isBlank()
                            && d.answer() != null && !d.answer().isBlank())
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** LLM 生成的问答对草稿（JSON 映射）。 */
    public record QaDraft(String question, String answer) {
    }
}
