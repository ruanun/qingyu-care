package com.qingyu.care.service;

import com.qingyu.care.entity.MessageReadReceipt;
import com.qingyu.care.enums.ReaderType;
import com.qingyu.care.repository.MessageReadReceiptRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReadReceiptServiceTest {

    @Mock MessageReadReceiptRepository repository;
    @InjectMocks ReadReceiptService service;

    @Test
    @DisplayName("已有水位线且传入值更大时，走原子 UPDATE、不再 insert")
    void 推进已有水位线() {
        when(repository.advanceWatermark(eq(1L), eq("AGENT"), eq(100L), eq(9L), any()))
                .thenReturn(1);

        long result = service.markRead(1L, ReaderType.AGENT, 100L, 9L);

        assertThat(result).isEqualTo(9L);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("首次回执：UPDATE 影响 0 行时插入新行")
    void 首次回执插入新行() {
        when(repository.advanceWatermark(anyLong(), any(), anyLong(), anyLong(), any())).thenReturn(0);
        when(repository.findBySessionIdAndReaderTypeAndReaderId(1L, ReaderType.AGENT, 100L))
                .thenReturn(Optional.empty());

        long result = service.markRead(1L, ReaderType.AGENT, 100L, 9L);

        assertThat(result).isEqualTo(9L);
        verify(repository).save(any(MessageReadReceipt.class));
    }

    @Test
    @DisplayName("传入值不比现值大时不回退，返回现值且不插入")
    void 水位线不回退() {
        when(repository.advanceWatermark(anyLong(), any(), anyLong(), anyLong(), any())).thenReturn(0);
        when(repository.findBySessionIdAndReaderTypeAndReaderId(1L, ReaderType.AGENT, 100L))
                .thenReturn(Optional.of(MessageReadReceipt.builder()
                        .sessionId(1L).readerType(ReaderType.AGENT).readerId(100L)
                        .upToMessageId(20L).readAt(LocalDateTime.now()).build()));

        long result = service.markRead(1L, ReaderType.AGENT, 100L, 5L);

        assertThat(result).as("并发下别人已推到 20，本次传 5 不该让它退回去").isEqualTo(20L);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("readerType 必须以字符串字面量传给原生 SQL，不能传枚举序号")
    void 原生SQL收到的是枚举名() {
        when(repository.advanceWatermark(anyLong(), any(), anyLong(), anyLong(), any())).thenReturn(1);

        service.markRead(1L, ReaderType.VISITOR, 7L, 3L);

        // 传成序号（"1"）会让 WHERE reader_type = '1' 永远匹配不上，静默 0 行
        verify(repository).advanceWatermark(eq(1L), eq("VISITOR"), eq(7L), eq(3L), any());
    }

    @Test
    @DisplayName("无回执时 currentWatermark 返回 null，不是 0")
    void 无回执时水位线为null() {
        when(repository.findBySessionIdAndReaderTypeAndReaderId(any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThat(service.currentWatermark(1L, ReaderType.AGENT, 100L)).isNull();
    }
}
