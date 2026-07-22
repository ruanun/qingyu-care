package com.qingyu.care.service;

import com.qingyu.care.dto.AbTestReportDTO;
import com.qingyu.care.entity.AbExperiment;
import com.qingyu.care.entity.AbVariant;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.enums.ExperimentStatus;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.AbExperimentRepository;
import com.qingyu.care.repository.AbTestReportDao;
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
    private final AbTestReportDao reportDao;

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

    /** 分桶结果三元组（落 ChatSession 三字段） */
    public record VariantAssignment(Long experimentId, String variantName, Long variantStrategyId) {}

    /**
     * 为新会话分桶（仅开放路径调用）。fail-soft：无运行实验或任何异常返回 empty，
     * 会话不参与实验、不阻断对话。
     */
    public java.util.Optional<VariantAssignment> assignForNewSession() {
        try {
            return repository.findFirstByStatus(ExperimentStatus.RUNNING)
                    .map(exp -> {
                        AbVariant v = pickVariant(exp.getVariants(),
                                java.util.concurrent.ThreadLocalRandom.current().nextInt(100));
                        return new VariantAssignment(exp.getId(), v.getName(), v.getStrategyId());
                    });
        } catch (Exception e) {
            log.warn("A/B 分桶失败，会话不参与实验: {}", e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /**
     * 纯函数：按累计权重区间取变体（roll ∈ [0,100)）。权重 0 的变体区间为空，永不选中。
     * static 免容器单测（同 ChatServiceImpl.meterContentStream 惯例）。
     */
    static AbVariant pickVariant(List<AbVariant> variants, int roll) {
        int cumulative = 0;
        for (AbVariant v : variants) {
            cumulative += v.getWeight();
            if (roll < cumulative) {
                return v;
            }
        }
        // 理论不可达（启动校验保证权重和=100），防御性兜底取末位
        return variants.get(variants.size() - 1);
    }

    /**
     * 对话时解析变体策略。empty = 走默认策略（未参与实验 / 实验已结束或被删 /
     * 策略被禁用或删除 / 异常，全部 fail-soft）。
     */
    public java.util.Optional<ChatStrategy> resolveVariantStrategy(Long experimentId, Long variantStrategyId) {
        if (experimentId == null || variantStrategyId == null) {
            return java.util.Optional.empty();
        }
        try {
            return repository.findById(experimentId)
                    .filter(e -> e.getStatus() == ExperimentStatus.RUNNING)
                    .flatMap(e -> strategyRepository.findById(variantStrategyId))
                    .filter(s -> Boolean.TRUE.equals(s.getEnabled()));
        } catch (Exception e) {
            log.warn("解析变体策略失败，回退默认策略: {}", e.getMessage());
            return java.util.Optional.empty();
        }
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

    /** 实验对比报表：先 JPA 取实验（租户/应用过滤校验归属），再 native 按变体聚合 */
    public AbTestReportDTO getReport(Long id) {
        AbExperiment exp = getById(id);
        var sessionRows = reportDao.aggregateSessions(id).stream()
                .collect(java.util.stream.Collectors.toMap(
                        AbTestReportDao.SessionRow::variantName, r -> r));
        var messageRows = reportDao.aggregateMessages(id).stream()
                .collect(java.util.stream.Collectors.toMap(
                        AbTestReportDao.MessageRow::variantName, r -> r));
        List<AbTestReportDTO.VariantReport> variants = exp.getVariants().stream().map(v -> {
            var s = sessionRows.get(v.getName());
            var m = messageRows.get(v.getName());
            long sessionCount = s != null ? s.sessionCount() : 0;
            long handover = s != null ? s.handoverCount() : 0;
            long resolved = s != null ? s.resolvedCount() : 0;
            long ended = s != null ? s.endedCount() : 0;
            long question = m != null ? m.questionCount() : 0;
            long aiMsg = m != null ? m.aiMsgCount() : 0;
            long up = m != null ? m.feedbackUp() : 0;
            long down = m != null ? m.feedbackDown() : 0;
            return new AbTestReportDTO.VariantReport(
                    v.getName(), v.getWeight(),
                    sessionCount, question, aiMsg,
                    m != null ? m.avgConfidence() : null,
                    up, down,
                    rate(up, up + down),
                    rate(m != null ? m.fallbackCount() : 0, aiMsg),
                    rate(m != null ? m.lowConfidenceCount() : 0, aiMsg),
                    rate(m != null ? m.hitCount() : 0, aiMsg),
                    rate(handover, sessionCount),
                    rate(resolved, ended));
        }).toList();
        return new AbTestReportDTO(exp.getId(), exp.getName(), exp.getStatus().name(),
                exp.getStartedAt(), exp.getEndedAt(), variants);
    }

    /** 纯函数：比率保留 4 位小数；分母 0 → null（static 免容器单测） */
    static Double rate(long numerator, long denominator) {
        if (denominator == 0) {
            return null;
        }
        return Math.round(numerator * 10000.0 / denominator) / 10000.0;
    }
}
