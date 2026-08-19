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
 * 守护回执表的数据库级约束——都是纯 Mockito 单测证明不了的。
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

    /**
     * 两行刻意使用<b>相同</b>的 readerId（100L），只让 readerType 成为唯一变量：
     * 若 {@code advanceWatermark} 的 SQL 意外漏掉 {@code reader_type} 过滤条件，
     * 本测试才会转红——用不同 readerId（如坐席 100L、访客 2L）测不出这一点，因为
     * 那样即便漏了 reader_type 条件，reader_id 不等也会让 UPDATE 天然只命中一行。
     * 现实中这种编号碰撞是可能的：VISITOR 的 readerId 是 chat_session.id，AGENT 的是
     * 坐席 user id，两个数值空间互相独立，取到同一个数值毫不意外。
     */
    @Test
    @DisplayName("同一会话下坐席与访客各一条水位线，即便 readerId 数值相同也互不干扰")
    void 坐席与访客的水位线互不干扰() {
        repository.saveAndFlush(receipt(2L, ReaderType.AGENT, 100L, 5L));
        repository.saveAndFlush(receipt(2L, ReaderType.VISITOR, 100L, 8L));

        int agentUpdated = repository.advanceWatermark(
                2L, ReaderType.AGENT.name(), 100L, 7L, LocalDateTime.now());

        assertThat(agentUpdated).isEqualTo(1);
        assertThat(repository.findBySessionIdAndReaderTypeAndReaderId(2L, ReaderType.AGENT, 100L))
                .get().extracting(MessageReadReceipt::getUpToMessageId).isEqualTo(7L);
        assertThat(repository.findBySessionIdAndReaderTypeAndReaderId(2L, ReaderType.VISITOR, 100L))
                .as("推进坐席水位线不该动到访客那一行——即便两行的 readerId 数值相同，"
                        + "reader_type 仍必须是过滤条件的一部分")
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

    /**
     * 守 {@code readerId NOT NULL}：PostgreSQL 的唯一约束里 {@code NULL != NULL}，
     * 若这条 NOT NULL 被误删（如实体上的 {@code @Column(nullable = false)} 或迁移脚本里的
     * {@code NOT NULL} 被拿掉），{@code (session_id, 'VISITOR', NULL)} 就能被反复插入任意多行——
     * 唯一约束形同虚设，同一读者的水位线变成多行并存，读出来哪一行全看运气，
     * 且整个过程<b>不抛任何异常、不报任何错</b>，只会在生产上表现为已读状态间歇性错乱。
     * 本用例钉住「插入 readerId=null 必须在数据库层被拒绝」，即上述后果的第一道防线。
     */
    @Test
    @DisplayName("readerId 为 null 时插入被数据库拒绝——放行的话会让同一读者的水位线多行并存且不报错")
    void readerId为null时插入被拒绝() {
        assertThatThrownBy(() -> repository.saveAndFlush(receipt(4L, ReaderType.VISITOR, null, 1L)))
                .as("reader_id 必须是 NOT NULL；一旦这条约束丢失，此处不再抛异常，"
                        + "而是能对同一 (session_id, reader_type) 反复插入多行 NULL readerId 的水位线")
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
