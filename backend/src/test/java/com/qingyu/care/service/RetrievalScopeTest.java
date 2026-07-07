package com.qingyu.care.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalScopeTest {

    @Test
    void 拼接filterExpression_含tenant与多库IN() {
        RetrievalScope scope = new RetrievalScope(1L, 2L, List.of(10L, 11L));
        assertThat(scope.toFilterExpression())
                .isEqualTo("tenantId == '1' && appId == '2' && knowledgeBaseId in ['10','11']");
    }

    @Test
    void 拼接filterExpression_无tenant省略子句() {
        RetrievalScope scope = new RetrievalScope(null, 2L, List.of(10L));
        assertThat(scope.toFilterExpression())
                .isEqualTo("appId == '2' && knowledgeBaseId in ['10']");
    }

    @Test
    void isEmpty_无app或无库为真() {
        assertThat(new RetrievalScope(1L, null, List.of(10L)).isEmpty()).isTrue();
        assertThat(new RetrievalScope(1L, 2L, List.of()).isEmpty()).isTrue();
        assertThat(new RetrievalScope(1L, 2L, null).isEmpty()).isTrue();
        assertThat(new RetrievalScope(1L, 2L, List.of(10L)).isEmpty()).isFalse();
    }

    @Test
    void 空范围拼接filterExpression_抛IllegalStateException() {
        assertThatThrownBy(() -> RetrievalScope.empty(1L).toFilterExpression())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void empty工厂_保留tenantId() {
        RetrievalScope scope = RetrievalScope.empty(1L);
        assertThat(scope.isEmpty()).isTrue();
        assertThat(scope.tenantId()).isEqualTo(1L);
    }
}
