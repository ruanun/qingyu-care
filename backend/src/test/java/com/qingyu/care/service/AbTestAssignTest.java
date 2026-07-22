package com.qingyu.care.service;

import com.qingyu.care.entity.AbExperiment;
import com.qingyu.care.entity.AbVariant;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.enums.ExperimentStatus;
import com.qingyu.care.repository.AbExperimentRepository;
import com.qingyu.care.repository.ChatStrategyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 分桶纯函数 + 会话分配 + 变体策略解析单测。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class AbTestAssignTest {

    @Mock AbExperimentRepository repository;
    @Mock ChatStrategyRepository strategyRepository;
    @InjectMocks AbTestService service;

    private AbVariant v(String name, Long strategyId, int weight) {
        return AbVariant.builder().name(name).strategyId(strategyId).weight(weight).build();
    }

    @Test
    void pickVariant_按累计权重区间取变体() {
        List<AbVariant> variants = List.of(v("A", 1L, 50), v("B", 2L, 50));
        assertThat(AbTestService.pickVariant(variants, 0).getName()).isEqualTo("A");
        assertThat(AbTestService.pickVariant(variants, 49).getName()).isEqualTo("A");
        assertThat(AbTestService.pickVariant(variants, 50).getName()).isEqualTo("B");
        assertThat(AbTestService.pickVariant(variants, 99).getName()).isEqualTo("B");
    }

    @Test
    void pickVariant_权重0变体永不选中() {
        List<AbVariant> variants = List.of(v("A", 1L, 0), v("B", 2L, 100));
        for (int roll = 0; roll < 100; roll++) {
            assertThat(AbTestService.pickVariant(variants, roll).getName()).isEqualTo("B");
        }
    }

    @Test
    void assign_无运行实验_返回empty() {
        when(repository.findFirstByStatus(ExperimentStatus.RUNNING)).thenReturn(Optional.empty());
        assertThat(service.assignForNewSession()).isEmpty();
    }

    @Test
    void assign_有运行实验_返回三元组() {
        AbExperiment exp = AbExperiment.builder().id(7L).name("e")
                .status(ExperimentStatus.RUNNING)
                .variants(new ArrayList<>(List.of(v("B", 42L, 100)))).build();
        when(repository.findFirstByStatus(ExperimentStatus.RUNNING)).thenReturn(Optional.of(exp));

        var assignment = service.assignForNewSession();

        assertThat(assignment).isPresent();
        assertThat(assignment.get().experimentId()).isEqualTo(7L);
        assertThat(assignment.get().variantName()).isEqualTo("B");
        assertThat(assignment.get().variantStrategyId()).isEqualTo(42L);
    }

    @Test
    void assign_仓库异常_failSoft返回empty() {
        when(repository.findFirstByStatus(ExperimentStatus.RUNNING))
                .thenThrow(new RuntimeException("db down"));
        assertThat(service.assignForNewSession()).isEmpty();
    }

    @Test
    void resolve_参数为空_返回empty() {
        assertThat(service.resolveVariantStrategy(null, 42L)).isEmpty();
        assertThat(service.resolveVariantStrategy(7L, null)).isEmpty();
    }

    @Test
    void resolve_实验已结束_返回empty() {
        AbExperiment ended = AbExperiment.builder().id(7L).name("e")
                .status(ExperimentStatus.ENDED).build();
        when(repository.findById(7L)).thenReturn(Optional.of(ended));
        assertThat(service.resolveVariantStrategy(7L, 42L)).isEmpty();
    }

    @Test
    void resolve_策略被禁用_返回empty() {
        AbExperiment running = AbExperiment.builder().id(7L).name("e")
                .status(ExperimentStatus.RUNNING).build();
        when(repository.findById(7L)).thenReturn(Optional.of(running));
        ChatStrategy disabled = ChatStrategy.builder().id(42L).name("s").scenario("default")
                .enabled(false).build();
        when(strategyRepository.findById(42L)).thenReturn(Optional.of(disabled));
        assertThat(service.resolveVariantStrategy(7L, 42L)).isEmpty();
    }

    @Test
    void resolve_正常_返回变体策略() {
        AbExperiment running = AbExperiment.builder().id(7L).name("e")
                .status(ExperimentStatus.RUNNING).build();
        when(repository.findById(7L)).thenReturn(Optional.of(running));
        ChatStrategy enabled = ChatStrategy.builder().id(42L).name("s").scenario("default")
                .enabled(true).build();
        when(strategyRepository.findById(42L)).thenReturn(Optional.of(enabled));
        assertThat(service.resolveVariantStrategy(7L, 42L)).contains(enabled);
    }

    @Test
    void resolve_仓库异常_failSoft返回empty() {
        when(repository.findById(7L)).thenThrow(new RuntimeException("db down"));
        assertThat(service.resolveVariantStrategy(7L, 42L)).isEmpty();
    }
}
