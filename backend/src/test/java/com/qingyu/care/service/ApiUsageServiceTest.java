package com.qingyu.care.service;

import com.qingyu.care.entity.SysApiUsage;
import com.qingyu.care.repository.SysApiUsageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiUsageServiceTest {

    @Mock SysApiUsageRepository repository;
    @InjectMocks ApiUsageService service;

    @Test
    void record_incrementsExistingRow_whenUpdateAffectsRow() {
        when(repository.incrementUsage(eq(1L), any(LocalDate.class), eq(1L), eq(10L))).thenReturn(1);

        service.record(9L, 1L, 1L, 10L);

        verify(repository).incrementUsage(eq(1L), any(LocalDate.class), eq(1L), eq(10L));
        verify(repository, never()).save(any());
    }

    @Test
    void record_insertsNewRow_whenUpdateAffectsNothing() {
        when(repository.incrementUsage(eq(1L), any(LocalDate.class), eq(1L), eq(10L))).thenReturn(0);

        service.record(9L, 1L, 1L, 10L);

        verify(repository).save(argThat(u ->
                u.getAppId().equals(1L) && u.getTenantId().equals(9L)
                        && u.getCallCount() == 1 && u.getMessageCount() == 1 && u.getTokenCount() == 10));
    }

    @Test
    void record_swallowsExceptions() {
        when(repository.incrementUsage(anyLong(), any(), anyLong(), anyLong()))
                .thenThrow(new RuntimeException("db down"));
        // 不应抛出
        service.record(9L, 1L, 1L, 10L);
    }

    @Test
    void record_retriesIncrement_whenInsertConflicts() {
        // 首次累加未命中（返回 0），insert 因唯一键冲突失败，应重试累加
        when(repository.incrementUsage(eq(1L), any(LocalDate.class), eq(1L), eq(10L))).thenReturn(0);
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("uk"));

        service.record(9L, 1L, 1L, 10L);

        // incrementUsage 被调用两次：初次 + 冲突后重试
        verify(repository, times(2)).incrementUsage(eq(1L), any(LocalDate.class), eq(1L), eq(10L));
    }
}
