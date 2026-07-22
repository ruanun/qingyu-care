package com.qingyu.care.service;

import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.enums.ExperimentStatus;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.AbExperimentRepository;
import com.qingyu.care.repository.ChatStrategyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 策略删除保护：被运行中 A/B 实验引用的策略禁删。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatStrategyDeleteGuardTest {

    @Mock ChatStrategyRepository repository;
    @Mock AbExperimentRepository abExperimentRepository;
    @InjectMocks ChatStrategyService service;

    @Test
    void 被运行实验引用_拒绝删除() {
        when(repository.findById(5L)).thenReturn(Optional.of(
                ChatStrategy.builder().id(5L).name("s").scenario("faq").isDefault(false).build()));
        when(abExperimentRepository.existsByStatusAndVariants_StrategyId(ExperimentStatus.RUNNING, 5L))
                .thenReturn(true);

        assertThatThrownBy(() -> service.deleteStrategy(5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("运行中的 A/B 实验");
        verify(repository, never()).deleteById(any());
    }

    @Test
    void 未被引用_正常删除() {
        when(repository.findById(5L)).thenReturn(Optional.of(
                ChatStrategy.builder().id(5L).name("s").scenario("faq").isDefault(false).build()));
        when(abExperimentRepository.existsByStatusAndVariants_StrategyId(ExperimentStatus.RUNNING, 5L))
                .thenReturn(false);

        service.deleteStrategy(5L);
        verify(repository).deleteById(5L);
    }
}
