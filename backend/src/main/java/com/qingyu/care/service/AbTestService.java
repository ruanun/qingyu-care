package com.qingyu.care.service;

import com.qingyu.care.entity.AbExperiment;
import com.qingyu.care.entity.AbVariant;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.enums.ExperimentStatus;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.AbExperimentRepository;
import com.qingyu.care.repository.ChatStrategyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A/B 实验服务：生命周期管理（本类）+ 分桶/变体解析/报表（后续任务追加）。
 * 实验实体继承 BaseTenantEntity，list/getById 等查询天然限定当前租户/应用范围。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AbTestService {

    private final AbExperimentRepository repository;
    private final ChatStrategyRepository strategyRepository;

    public List<AbExperiment> list() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    public AbExperiment getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("A/B 实验", id.toString()));
    }

    /** 创建：强制 DRAFT，清空运行时字段；变体只做基础校验（和=100 延迟到 start 强校验） */
    @Transactional
    public AbExperiment create(AbExperiment input) {
        input.setId(null);
        input.setStatus(ExperimentStatus.DRAFT);
        input.setStartedAt(null);
        input.setEndedAt(null);
        validateVariantBasics(input.getVariants());
        return repository.save(input);
    }

    /** 编辑：仅 DRAFT 可改（RUNNING 后变体锁定） */
    @Transactional
    public AbExperiment update(Long id, AbExperiment patch) {
        AbExperiment existing = getById(id);
        if (existing.getStatus() != ExperimentStatus.DRAFT) {
            throw new BusinessException("仅草稿状态的实验可编辑");
        }
        if (patch.getName() != null) existing.setName(patch.getName());
        if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
        if (patch.getVariants() != null && !patch.getVariants().isEmpty()) {
            validateVariantBasics(patch.getVariants());
            existing.getVariants().clear();
            existing.getVariants().addAll(patch.getVariants());
        }
        return repository.save(existing);
    }

    /** 启动：全量校验后 DRAFT → RUNNING */
    @Transactional
    public AbExperiment start(Long id) {
        AbExperiment exp = getById(id);
        if (exp.getStatus() != ExperimentStatus.DRAFT) {
            throw new BusinessException("仅草稿状态的实验可启动");
        }
        List<AbVariant> variants = exp.getVariants();
        if (variants == null || variants.size() < 2) {
            throw new BusinessException("实验至少需要 2 个变体");
        }
        validateVariantBasics(variants);
        int sum = variants.stream().mapToInt(AbVariant::getWeight).sum();
        if (sum != 100) {
            throw new BusinessException("变体权重合计必须为 100，当前为 " + sum);
        }
        for (AbVariant v : variants) {
            ChatStrategy s = strategyRepository.findById(v.getStrategyId())
                    .orElseThrow(() -> new BusinessException(
                            "变体 " + v.getName() + " 绑定的策略不存在（id=" + v.getStrategyId() + "）"));
            if (!Boolean.TRUE.equals(s.getEnabled())) {
                throw new BusinessException("变体 " + v.getName() + " 绑定的策略未启用");
            }
        }
        if (repository.existsByStatus(ExperimentStatus.RUNNING)) {
            throw new BusinessException("当前应用已有运行中的实验，请先结束后再启动新实验");
        }
        exp.setStatus(ExperimentStatus.RUNNING);
        exp.setStartedAt(LocalDateTime.now());
        return repository.save(exp);
    }

    /** 结束：RUNNING → ENDED，存量会话下一条消息即回归默认策略 */
    @Transactional
    public AbExperiment end(Long id) {
        AbExperiment exp = getById(id);
        if (exp.getStatus() != ExperimentStatus.RUNNING) {
            throw new BusinessException("仅运行中的实验可结束");
        }
        exp.setStatus(ExperimentStatus.ENDED);
        exp.setEndedAt(LocalDateTime.now());
        return repository.save(exp);
    }

    /** 删除：仅 DRAFT / ENDED（存量会话标记悬空无害，resolve 时自动回默认） */
    @Transactional
    public void delete(Long id) {
        AbExperiment exp = getById(id);
        if (exp.getStatus() == ExperimentStatus.RUNNING) {
            throw new BusinessException("运行中的实验不能删除，请先结束");
        }
        repository.delete(exp);
    }

    /** 基础合法性：变体名非空不重复、权重 0~100、必须绑定策略（权重和只在 start 强校验） */
    private void validateVariantBasics(List<AbVariant> variants) {
        if (variants == null || variants.isEmpty()) {
            return;
        }
        long distinct = variants.stream().map(AbVariant::getName)
                .filter(n -> n != null && !n.isBlank()).distinct().count();
        if (distinct != variants.size()) {
            throw new BusinessException("变体名不能为空且不能重复");
        }
        for (AbVariant v : variants) {
            if (v.getWeight() == null || v.getWeight() < 0 || v.getWeight() > 100) {
                throw new BusinessException("变体权重必须为 0~100 的整数");
            }
            if (v.getStrategyId() == null) {
                throw new BusinessException("变体必须绑定一条问答策略");
            }
        }
    }
}
