package com.qingyu.care.service.embedding;

import com.qingyu.care.entity.AiModelConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识内容（文档切块 / QA）写入向量库的唯一出口：负责「用哪个模型嵌、打哪个标签」。
 * 调用方只管准备好切块与 metadata（租户、应用、知识库、可见性等），<b>不要自己写 embeddingModel 这个键</b>。
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
public class KnowledgeVectorWriter {

    /** metadata 里的模型标签键，检索护栏与按标签删除都认它。 */
    public static final String TAG_KEY = "embeddingModel";

    private final EmbeddingModelResolver resolver;
    private final EmbeddingModelRegistry registry;

    /** 按应用当前的写入目标写入（切换进行中时双写当前模型与目标模型）。 */
    public void write(Long appId, List<Document> chunks) {
        for (AiModelConfig model : resolver.writeTargets(appId)) {
            writeFor(model, chunks);
        }
    }

    /** 只用指定模型写一份（迁移任务往目标模型补向量时用）。 */
    public void writeFor(AiModelConfig model, List<Document> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        String tag = EmbeddingTag.of(model);
        // 每个目标各复制一份：行主键取自 Document.id，同一批切块双写时必须是不同的行
        List<Document> tagged = chunks.stream().map(c -> {
            Map<String, Object> md = new HashMap<>(c.getMetadata());
            md.put(TAG_KEY, tag);
            return Document.builder().text(c.getText()).metadata(md).build();
        }).toList();
        registry.vectorStore(model).add(tagged);
    }
}
