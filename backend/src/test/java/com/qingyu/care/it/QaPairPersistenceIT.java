package com.qingyu.care.it;

import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.entity.QaPair;
import com.qingyu.care.enums.QaPairSource;
import com.qingyu.care.enums.QaPairStatus;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.repository.QaPairRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 QaPair 在真实 Postgres 容器持久化+回读，及按状态派生查询。
 *
 * @author ruan
 */
class QaPairPersistenceIT extends AbstractPostgresIT {

    @Autowired
    private QaPairRepository qaPairRepository;
    @Autowired
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Test
    void qaPairRoundTripAndStatusQuery() {
        KnowledgeBase kb = knowledgeBaseRepository.saveAndFlush(
                KnowledgeBase.builder().name("FAQ库").enabled(true).build());

        qaPairRepository.saveAndFlush(QaPair.builder()
                .knowledgeBase(kb).question("如何退货").answer("7天内订单页申请")
                .source(QaPairSource.MANUAL).status(QaPairStatus.ACTIVE).build());
        qaPairRepository.saveAndFlush(QaPair.builder()
                .knowledgeBase(kb).question("草稿问").answer("草稿答")
                .source(QaPairSource.GENERATED).status(QaPairStatus.DRAFT).sourceDocumentId(99L).build());

        assertThat(qaPairRepository.findByKnowledgeBaseId(kb.getId())).hasSize(2);
        assertThat(qaPairRepository.findByKnowledgeBaseIdAndStatus(kb.getId(), QaPairStatus.DRAFT))
                .singleElement()
                .satisfies(q -> {
                    assertThat(q.getQuestion()).isEqualTo("草稿问");
                    assertThat(q.getSourceDocumentId()).isEqualTo(99L);
                    assertThat(q.getSource()).isEqualTo(QaPairSource.GENERATED);
                });
    }
}
