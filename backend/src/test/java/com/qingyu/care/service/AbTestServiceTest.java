package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.AbTestUpdateRequest;
import com.qingyu.care.entity.AbExperiment;
import com.qingyu.care.entity.AbVariant;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.enums.ExperimentStatus;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.AbExperimentRepository;
import com.qingyu.care.repository.AbTestReportDao;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A/B 实验生命周期与校验单测。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class AbTestServiceTest {

    @Mock AbExperimentRepository repository;
    @Mock ChatStrategyRepository strategyRepository;
    @Mock AbTestReportDao reportDao;
    @InjectMocks AbTestService service;

    private AbExperiment draft(AbVariant... variants) {
        return AbExperiment.builder().id(1L).name("实验")
                .status(ExperimentStatus.DRAFT)
                .variants(new ArrayList<>(List.of(variants))).build();
    }

    private AbVariant v(String name, Long strategyId, int weight) {
        return AbVariant.builder().name(name).strategyId(strategyId).weight(weight).build();
    }

    private ChatStrategy enabledStrategy(Long id) {
        return ChatStrategy.builder().id(id).name("s" + id).scenario("default").enabled(true).build();
    }

    @Test
    void list_有appId_按appId收窄() {
        AbExperiment exp = draft(v("A", 1L, 50), v("B", 2L, 50));
        when(repository.findByAppIdOrderByCreatedAtDesc(10L)).thenReturn(List.of(exp));
        TenantContext.setAppId(10L);
        try {
            List<AbExperiment> result = service.list();

            assertThat(result).containsExactly(exp);
            verify(repository, never()).findAllByOrderByCreatedAtDesc();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void list_无appId_返空且不查库() {
        List<AbExperiment> result = service.list();

        assertThat(result).isEmpty();
        verify(repository, never()).findByAppIdOrderByCreatedAtDesc(any());
        verify(repository, never()).findAllByOrderByCreatedAtDesc();
    }

    @Test
    void create_强制置为草稿并清运行时字段() {
        AbExperiment input = AbExperiment.builder().id(99L).name("新实验")
                .status(ExperimentStatus.RUNNING)
                .variants(new ArrayList<>(List.of(v("A", 1L, 50), v("B", 2L, 50)))).build();
        input.setTenantId(999L);
        input.setAppId(888L);
        when(repository.save(any(AbExperiment.class))).thenAnswer(inv -> inv.getArgument(0));

        AbExperiment saved = service.create(input);

        assertThat(saved.getId()).isNull();
        assertThat(saved.getStatus()).isEqualTo(ExperimentStatus.DRAFT);
        assertThat(saved.getStartedAt()).isNull();
        assertThat(saved.getTenantId()).isNull();
        assertThat(saved.getAppId()).isNull();
    }

    @Test
    void getById_他租户实验_按不存在处理() {
        AbExperiment exp = draft(v("A", 1L, 50), v("B", 2L, 50));
        exp.setTenantId(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(exp));
        TenantContext.setTenantId(2L);
        try {
            assertThatThrownBy(() -> service.getById(1L))
                    .isInstanceOf(ResourceNotFoundException.class);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void start_变体绑他租户策略_拒绝() {
        AbExperiment exp = draft(v("A", 1L, 50), v("B", 2L, 50));
        when(repository.findById(1L)).thenReturn(Optional.of(exp));
        ChatStrategy otherTenantStrategy = enabledStrategy(1L);
        otherTenantStrategy.setTenantId(9L);
        when(strategyRepository.findById(1L)).thenReturn(Optional.of(otherTenantStrategy));
        TenantContext.setTenantId(2L);
        try {
            assertThatThrownBy(() -> service.start(1L))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("不存在");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void start_成功_置运行状态与开始时间() {
        AbExperiment exp = draft(v("A", 1L, 50), v("B", 2L, 50));
        when(repository.findById(1L)).thenReturn(Optional.of(exp));
        when(strategyRepository.findById(1L)).thenReturn(Optional.of(enabledStrategy(1L)));
        when(strategyRepository.findById(2L)).thenReturn(Optional.of(enabledStrategy(2L)));
        when(repository.existsByStatus(ExperimentStatus.RUNNING)).thenReturn(false);
        when(repository.save(any(AbExperiment.class))).thenAnswer(inv -> inv.getArgument(0));

        AbExperiment started = service.start(1L);

        assertThat(started.getStatus()).isEqualTo(ExperimentStatus.RUNNING);
        assertThat(started.getStartedAt()).isNotNull();
    }

    @Test
    void start_权重0变体合法() {
        AbExperiment exp = draft(v("A", 1L, 100), v("B", 2L, 0));
        when(repository.findById(1L)).thenReturn(Optional.of(exp));
        when(strategyRepository.findById(1L)).thenReturn(Optional.of(enabledStrategy(1L)));
        when(strategyRepository.findById(2L)).thenReturn(Optional.of(enabledStrategy(2L)));
        when(repository.existsByStatus(ExperimentStatus.RUNNING)).thenReturn(false);
        when(repository.save(any(AbExperiment.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.start(1L).getStatus()).isEqualTo(ExperimentStatus.RUNNING);
    }

    @Test
    void start_非草稿_拒绝() {
        AbExperiment exp = draft(v("A", 1L, 50), v("B", 2L, 50));
        exp.setStatus(ExperimentStatus.ENDED);
        when(repository.findById(1L)).thenReturn(Optional.of(exp));
        assertThatThrownBy(() -> service.start(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("草稿");
    }

    @Test
    void start_变体不足两个_拒绝() {
        when(repository.findById(1L)).thenReturn(Optional.of(draft(v("A", 1L, 100))));
        assertThatThrownBy(() -> service.start(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("2 个变体");
    }

    @Test
    void start_权重和非100_拒绝() {
        when(repository.findById(1L)).thenReturn(Optional.of(draft(v("A", 1L, 60), v("B", 2L, 50))));
        assertThatThrownBy(() -> service.start(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("100");
    }

    @Test
    void start_变体名重复_拒绝() {
        when(repository.findById(1L)).thenReturn(Optional.of(draft(v("A", 1L, 50), v("A", 2L, 50))));
        assertThatThrownBy(() -> service.start(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("重复");
    }

    @Test
    void start_策略未启用_拒绝() {
        AbExperiment exp = draft(v("A", 1L, 50), v("B", 2L, 50));
        when(repository.findById(1L)).thenReturn(Optional.of(exp));
        ChatStrategy disabled = enabledStrategy(1L);
        disabled.setEnabled(false);
        when(strategyRepository.findById(1L)).thenReturn(Optional.of(disabled));
        assertThatThrownBy(() -> service.start(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("未启用");
    }

    @Test
    void start_已有运行中实验_拒绝() {
        AbExperiment exp = draft(v("A", 1L, 50), v("B", 2L, 50));
        when(repository.findById(1L)).thenReturn(Optional.of(exp));
        when(strategyRepository.findById(1L)).thenReturn(Optional.of(enabledStrategy(1L)));
        when(strategyRepository.findById(2L)).thenReturn(Optional.of(enabledStrategy(2L)));
        when(repository.existsByStatus(ExperimentStatus.RUNNING)).thenReturn(true);
        assertThatThrownBy(() -> service.start(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("运行中");
    }

    @Test
    void end_运行中_成功置结束() {
        AbExperiment exp = draft(v("A", 1L, 50), v("B", 2L, 50));
        exp.setStatus(ExperimentStatus.RUNNING);
        when(repository.findById(1L)).thenReturn(Optional.of(exp));
        when(repository.save(any(AbExperiment.class))).thenAnswer(inv -> inv.getArgument(0));

        AbExperiment ended = service.end(1L);
        assertThat(ended.getStatus()).isEqualTo(ExperimentStatus.ENDED);
        assertThat(ended.getEndedAt()).isNotNull();
    }

    @Test
    void end_非运行中_拒绝() {
        when(repository.findById(1L)).thenReturn(Optional.of(draft(v("A", 1L, 50), v("B", 2L, 50))));
        assertThatThrownBy(() -> service.end(1L)).isInstanceOf(BusinessException.class);
    }

    private AbTestUpdateRequest patch(String name) {
        AbTestUpdateRequest req = new AbTestUpdateRequest();
        req.setName(name);
        return req;
    }

    @Test
    void update_非草稿_拒绝() {
        AbExperiment exp = draft(v("A", 1L, 50), v("B", 2L, 50));
        exp.setStatus(ExperimentStatus.RUNNING);
        when(repository.findById(1L)).thenReturn(Optional.of(exp));
        assertThatThrownBy(() -> service.update(1L, patch("改名")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("草稿");
    }

    /**
     * 守的是 {@code AbTestUpdateRequest} 的javadoc 点名的那条前车之鉴：改用专用 DTO 前，
     * {@code update} 直收 {@code AbExperiment} 实体虽然「无害纯属侥幸」（variants 的初始化器
     * 恰好被一个 {@code !isEmpty()} 挡住），但那不是设计出来的安全网。这条测试钉住行为本身：
     * 只传 name 时，description 与 variants 都不该被清空。
     */
    @Test
    void update_只传name_不清空description和variants() {
        AbExperiment exp = draft(v("A", 1L, 50), v("B", 2L, 50));
        exp.setDescription("原描述");
        when(repository.findById(1L)).thenReturn(Optional.of(exp));
        when(repository.save(any(AbExperiment.class))).thenAnswer(inv -> inv.getArgument(0));

        AbExperiment updated = service.update(1L, patch("新名称"));

        assertThat(updated.getName()).isEqualTo("新名称");
        assertThat(updated.getDescription()).isEqualTo("原描述");
        assertThat(updated.getVariants()).extracting(AbVariant::getName)
                .containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void delete_运行中_拒绝() {
        AbExperiment exp = draft(v("A", 1L, 50), v("B", 2L, 50));
        exp.setStatus(ExperimentStatus.RUNNING);
        when(repository.findById(1L)).thenReturn(Optional.of(exp));
        assertThatThrownBy(() -> service.delete(1L)).isInstanceOf(BusinessException.class);
        verify(repository, never()).delete(any(AbExperiment.class));
    }

    @Test
    void delete_草稿_成功() {
        AbExperiment exp = draft(v("A", 1L, 50), v("B", 2L, 50));
        when(repository.findById(1L)).thenReturn(Optional.of(exp));
        service.delete(1L);
        verify(repository).delete(exp);
    }
}
