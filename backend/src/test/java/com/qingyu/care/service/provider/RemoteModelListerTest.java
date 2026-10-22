package com.qingyu.care.service.provider;

import com.qingyu.care.enums.ModelType;
import com.qingyu.care.exception.InvalidOperationException;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 远端模型列表的解析、类型预判与响应体上限（出站本身走 ModelEndpointGuardInterceptor，已有专门测试）。
 *
 * @author ruan
 */
class RemoteModelListerTest {

    private final RemoteModelLister lister = new RemoteModelLister(null, new ObjectMapper());

    @Test
    void 解析OpenAI兼容列表_按名称预判类型_用不上的类型不列出() {
        List<RemoteModelLister.RemoteModel> models = lister.parse("""
                {"object":"list","data":[
                  {"id":"qwen-max","object":"model"},
                  {"id":"text-embedding-v4"},
                  {"id":"BAAI/bge-m3"},
                  {"id":"gte-rerank-v2"},
                  {"id":"whisper-1"},
                  {"id":""}
                ]}""");

        assertThat(models).extracting(RemoteModelLister.RemoteModel::id)
                .containsExactly("BAAI/bge-m3", "qwen-max", "text-embedding-v4");
        assertThat(models).filteredOn(m -> m.suggestedType() == ModelType.EMBEDDING)
                .extracting(RemoteModelLister.RemoteModel::id).containsExactlyInAnyOrder("BAAI/bge-m3", "text-embedding-v4");
    }

    @Test
    void 不是OpenAI兼容格式_给出可读提示() {
        assertThatThrownBy(() -> lister.parse("{\"models\":[]}")).isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("手动填写");
        assertThatThrownBy(() -> lister.parse("<html>")).isInstanceOf(InvalidOperationException.class);
    }

    @Test
    void 响应体超过上限_拒绝而不是读进内存() {
        String big = "x".repeat((int) RemoteModelLister.MAX_BODY_BYTES + 10);
        ResponseBody body = ResponseBody.create(big, MediaType.get("application/json"));

        assertThatThrownBy(() -> RemoteModelLister.readBounded(body)).isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("过大");
    }

    @Test
    void 上限以内_原样读出() throws Exception {
        ResponseBody body = ResponseBody.create("{\"data\":[]}", MediaType.get("application/json"));

        assertThat(RemoteModelLister.readBounded(body)).isEqualTo("{\"data\":[]}");
    }
}
