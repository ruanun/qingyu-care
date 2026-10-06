package com.qingyu.care.service.embedding;

import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.exception.InvalidOperationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 向量模型的连通性 + 维度探测：真实调用一次 embeddings。
 *
 * <p>保存向量模型前必须过它（学 MaxKB 的「保存即校验」）：向量表的列维度是建表时定死的，模型输出维度对不上，
 * 摄取时每一批写入都会被 PG 拒掉，而文档只会显示「处理失败」，看不出是模型配错了。
 * 有的模型不认 {@code dimensions} 参数、照样返回自己的原生维度，所以必须看实际返回的长度，不能只信配置。
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
public class EmbeddingModelProbe {

    private final EmbeddingModelRegistry registry;

    /** 探测结果：实际维度与耗时（毫秒）。 */
    public record Result(int dimensions, long elapsedMs) {
    }

    /**
     * 探测并要求维度与全局配置一致。
     *
     * @throws InvalidOperationException 连不上或维度不符，消息可直接给用户看
     */
    public Result requireCompatible(AiModelConfig config) {
        long start = System.currentTimeMillis();
        float[] vector;
        try {
            vector = registry.buildUncached(config).embed("连接测试");
        } catch (Exception e) {
            throw new InvalidOperationException("向量模型连接失败：" + com.qingyu.care.service.ModelUpstreamErrors.describe(e));
        }
        long elapsed = System.currentTimeMillis() - start;
        int expected = registry.dimensions();
        if (vector == null || vector.length != expected) {
            int actual = vector == null ? 0 : vector.length;
            throw new InvalidOperationException("向量维度不符：该模型返回 " + actual + " 维，平台要求 " + expected
                    + " 维。请换用支持输出 " + expected + " 维的模型（多数新模型可通过 dimensions 参数指定）");
        }
        return new Result(vector.length, elapsed);
    }
}
