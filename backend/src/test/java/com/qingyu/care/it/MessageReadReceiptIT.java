package com.qingyu.care.it;

import com.qingyu.care.entity.MessageReadReceipt;
import com.qingyu.care.enums.ReaderType;
import com.qingyu.care.repository.MessageReadReceiptRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 守护回执表的两条数据库级约束——都是纯 Mockito 单测证明不了的。
 *
 * @author ruan
 */
class MessageReadReceiptIT extends AbstractPostgresIT {

    @Autowired MessageReadReceiptRepository repository;

    private MessageReadReceipt receipt(Long sessionId, ReaderType type, Long readerId, Long upTo) {
        return MessageReadReceipt.builder()
                .sessionId(sessionId).readerType(type).readerId(readerId)
                .upToMessageId(upTo).readAt(LocalDateTime.now()).build();
    }

    @Test
    @DisplayName("同一会话同一读者不能有两条水位线")
    void 唯一约束阻止重复水位线() {
        repository.saveAndFlush(receipt(1L, ReaderType.AGENT, 100L, 5L));

        assertThatThrownBy(() -> repository.saveAndFlush(receipt(1L, ReaderType.AGENT, 100L, 9L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("同一会话下坐席与访客各一条水位线，互不干扰")
    void 坐席与访客的水位线互不干扰() {
        repository.saveAndFlush(receipt(2L, ReaderType.AGENT, 100L, 5L));
        repository.saveAndFlush(receipt(2L, ReaderType.VISITOR, 2L, 8L));

        int agentUpdated = repository.advanceWatermark(
                2L, ReaderType.AGENT.name(), 100L, 7L, LocalDateTime.now());

        assertThat(agentUpdated).isEqualTo(1);
        assertThat(repository.findBySessionIdAndReaderTypeAndReaderId(2L, ReaderType.AGENT, 100L))
                .get().extracting(MessageReadReceipt::getUpToMessageId).isEqualTo(7L);
        assertThat(repository.findBySessionIdAndReaderTypeAndReaderId(2L, ReaderType.VISITOR, 2L))
                .as("推进坐席水位线不该动到访客那一行")
                .get().extracting(MessageReadReceipt::getUpToMessageId).isEqualTo(8L);
    }

    @Test
    @DisplayName("传入更小的 upToMessageId 不推进，返回 0 行")
    void 水位线不回退() {
        repository.saveAndFlush(receipt(3L, ReaderType.AGENT, 100L, 10L));

        int updated = repository.advanceWatermark(
                3L, ReaderType.AGENT.name(), 100L, 4L, LocalDateTime.now());

        assertThat(updated).as("比现值小，不该更新").isZero();
        assertThat(repository.findBySessionIdAndReaderTypeAndReaderId(3L, ReaderType.AGENT, 100L))
                .get().extracting(MessageReadReceipt::getUpToMessageId).isEqualTo(10L);
    }
}
