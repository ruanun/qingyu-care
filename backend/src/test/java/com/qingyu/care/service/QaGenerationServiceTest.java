package com.qingyu.care.service;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QaGenerationServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parseQaJson_extractsArrayEvenWithSurroundingText() {
        String raw = "好的，以下是问答对：\n[{\"question\":\"如何退货\",\"answer\":\"7天内申请\"}]\n希望有用";
        var list = QaGenerationService.parseQaJson(raw, mapper);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).question()).isEqualTo("如何退货");
        assertThat(list.get(0).answer()).isEqualTo("7天内申请");
    }

    @Test
    void parseQaJson_filtersBlankAndReturnsEmptyOnGarbage() {
        assertThat(QaGenerationService.parseQaJson("不是 JSON", mapper)).isEmpty();
        assertThat(QaGenerationService.parseQaJson(null, mapper)).isEmpty();
        assertThat(QaGenerationService.parseQaJson("[{\"question\":\"\",\"answer\":\"x\"}]", mapper)).isEmpty();
    }
}
