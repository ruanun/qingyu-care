package com.qingyu.care.service;

import com.qingyu.care.dto.CoverageAnalyticsDTO;
import com.qingyu.care.dto.HotTopicsDTO;
import com.qingyu.care.dto.MissedQuestionDTO;
import com.qingyu.care.dto.PageDTO;
import com.qingyu.care.dto.QualityAnalyticsDTO;
import com.qingyu.care.dto.UsageAnalyticsDTO;
import com.qingyu.care.dto.UsageAnalyticsDTO.RankingItem;
import com.qingyu.care.dto.UsageAnalyticsDTO.Summary;
import com.qingyu.care.dto.UsageAnalyticsDTO.TrendPoint;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.StatDocHitDailyRepository;
import com.qingyu.care.repository.StatHotKeywordDailyRepository;
import com.qingyu.care.repository.StatQualityDailyRepository;
import com.qingyu.care.repository.SysApiUsageRepository;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.repository.projection.DocHitRankRow;
import com.qingyu.care.repository.projection.DocIdNameRow;
import com.qingyu.care.repository.projection.MissedQuestionRow;
import com.qingyu.care.repository.projection.QualityDailyRow;
import com.qingyu.care.repository.projection.SessionDailyRow;
import com.qingyu.care.repository.projection.SessionSummaryRow;
import com.qingyu.care.repository.projection.UsageDailyRow;
import com.qingyu.care.repository.projection.UsageRankRow;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用量统计看板聚合服务：按当前登录用户角色自适应聚合范围。
 * 角色判定复用 {@link AuthService}（与 TenantDataAspect 同源），不另造一套。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    /** 排行榜展示条数上限 */
    private static final int TOP_N = 10;

    private final SysApiUsageRepository usageRepository;
    private final ChatSessionRepository sessionRepository;
    private final TenantRepository tenantRepository;
    private final ApplicationRepository applicationRepository;
    private final AuthService authService;
    private final StatQualityDailyRepository qualityRepository;
    private final StatHotKeywordDailyRepository hotKeywordRepository;
    private final StatDocHitDailyRepository docHitRepository;
    private final ChatMessageRepository messageRepository;
    private final DocumentRepository documentRepository;

    /**
     * 获取用量看板数据。
     *
     * @param days      时间跨度（7/30/90，非法值回落 30）
     * @param appIdFilter 应用下钻过滤（平台管理员视角忽略此参数）
     * @return 包含汇总、趋势、排行的看板 DTO
     */
    public UsageAnalyticsDTO getUsage(int days, Long appIdFilter) {
        int span = normalizeDays(days);
        LocalDate today = LocalDate.now();
        LocalDate fromDate = today.minusDays(span - 1L);
        LocalDateTime fromDateTime = fromDate.atStartOfDay();

        boolean platform = authService.isPlatformAdmin();
        // 平台管理员跨所有租户聚合；租户用户限本租户
        Long tenantId = platform ? null : authService.getCurrentTenantId();
        // 平台视角不支持应用下钻（appId 置 null 忽略）
        Long appId = platform ? null : appIdFilter;

        // ===== 趋势数据（按天，补零） =====
        Map<LocalDate, UsageDailyRow> usageByDate = usageRepository
                .aggregateDaily(fromDate, tenantId, appId).stream()
                .collect(Collectors.toMap(UsageDailyRow::getDate, Function.identity(), (a, b) -> a));

        Map<LocalDate, Long> sessionsByDate = sessionRepository
                .aggregateDailySessions(fromDateTime, tenantId, appId).stream()
                .collect(Collectors.toMap(SessionDailyRow::getDate, SessionDailyRow::getSessions, (a, b) -> a));

        List<TrendPoint> trend = new ArrayList<>(span);
        for (int i = 0; i < span; i++) {
            LocalDate d = fromDate.plusDays(i);
            UsageDailyRow u = usageByDate.get(d);
            trend.add(new TrendPoint(d,
                    u != null ? u.getCalls() : 0L,
                    u != null ? u.getTokens() : 0L,
                    sessionsByDate.getOrDefault(d, 0L)));
        }

        // ===== 汇总指标 =====
        long totalCalls = trend.stream().mapToLong(TrendPoint::calls).sum();
        long totalTokens = trend.stream().mapToLong(TrendPoint::tokens).sum();
        // 消息数从 usage 原始行统计（趋势点中 tokens 为聚合后，messages 单独取）
        long totalMessages = usageByDate.values().stream().mapToLong(UsageDailyRow::getMessages).sum();

        SessionSummaryRow ss = sessionRepository.aggregateSessionSummary(fromDateTime, tenantId, appId);
        // AI 解决率 = AI解决数 / 已结束数（分母为 ended，非 total）
        double aiRate = ss.getEnded() > 0
                ? round1((double) ss.getAiResolved() / ss.getEnded() * 100) : 0.0;
        // 转人工率 = 转人工数 / 总会话数
        double handoverRate = ss.getTotal() > 0
                ? round1((double) ss.getHandover() / ss.getTotal() * 100) : 0.0;

        Summary summary = new Summary(totalCalls, totalTokens, totalMessages,
                ss.getTotal(), aiRate, handoverRate);

        // ===== 排行榜（平台按租户；租户按应用） =====
        List<RankingItem> ranking = platform
                ? buildTenantRanking(fromDate)
                : buildAppRanking(fromDate, tenantId);

        return new UsageAnalyticsDTO(summary, trend, ranking, platform ? "PLATFORM" : "TENANT");
    }

    /** 平台视角：Top N 租户排行，回填租户名称 */
    private List<RankingItem> buildTenantRanking(LocalDate from) {
        List<UsageRankRow> rows = usageRepository.rankByTenant(from, PageRequest.of(0, TOP_N));
        Map<Long, String> names = tenantRepository.findAllById(refIds(rows)).stream()
                .collect(Collectors.toMap(Tenant::getId, Tenant::getName));
        return rows.stream()
                .map(r -> new RankingItem(r.getRefId(),
                        names.getOrDefault(r.getRefId(), "未知租户"),
                        r.getCalls(), r.getTokens()))
                .toList();
    }

    /** 租户视角：本租户内 Top N 应用排行，回填应用名称 */
    private List<RankingItem> buildAppRanking(LocalDate from, Long tenantId) {
        List<UsageRankRow> rows = usageRepository.rankByApp(from, tenantId, PageRequest.of(0, TOP_N));
        Map<Long, String> names = applicationRepository.findAllById(refIds(rows)).stream()
                .collect(Collectors.toMap(Application::getId, Application::getName));
        return rows.stream()
                .map(r -> new RankingItem(r.getRefId(),
                        names.getOrDefault(r.getRefId(), "未知应用"),
                        r.getCalls(), r.getTokens()))
                .toList();
    }

    /** 从排行行中提取有效 refId 列表（过滤 null） */
    private List<Long> refIds(List<UsageRankRow> rows) {
        return rows.stream().map(UsageRankRow::getRefId).filter(Objects::nonNull).toList();
    }

    /**
     * 校准 days 参数：仅允许 7/30/90，其余回落默认值 30。
     */
    private int normalizeDays(int days) {
        return (days == 7 || days == 90) ? days : 30;
    }

    /** 保留一位小数（百分比显示用） */
    private double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    // ===== Phase 4.4 分析三件套 =====

    /** 视角解析（与 getUsage 同规则）：平台跨租户；租户限本租户，appId 下钻仅租户视角有效 */
    private Scope resolveScope(Long appIdFilter) {
        boolean platform = authService.isPlatformAdmin();
        Long tenantId = platform ? null : authService.getCurrentTenantId();
        Long appId = platform ? null : appIdFilter;
        return new Scope(platform, tenantId, appId);
    }

    private record Scope(boolean platform, Long tenantId, Long appId) {
    }

    /** 对话质量看板（rollup T+1：窗口为 [cutoff-span+1, cutoff]，cutoff=昨日） */
    public QualityAnalyticsDTO getQuality(int days, Long appIdFilter) {
        int span = normalizeDays(days);
        LocalDate cutoff = LocalDate.now().minusDays(1);
        LocalDate from = cutoff.minusDays(span - 1L);
        Scope sc = resolveScope(appIdFilter);

        Map<LocalDate, QualityDailyRow> byDate = qualityRepository
                .aggregateDaily(from, sc.tenantId(), sc.appId()).stream()
                .collect(Collectors.toMap(QualityDailyRow::getDate, Function.identity(), (a, b) -> a));

        List<QualityAnalyticsDTO.TrendPoint> trend = new ArrayList<>(span);
        long aiMsg = 0, lowConf = 0, fallback = 0, up = 0, down = 0;
        long sessions = 0, handover = 0, resolved = 0, ended = 0;
        double confSum = 0;
        for (int i = 0; i < span; i++) {
            LocalDate d = from.plusDays(i);
            QualityDailyRow r = byDate.get(d);
            long dayAi = r != null ? r.getAiMsgCount() : 0;
            double dayConfSum = (r != null && r.getConfSum() != null) ? r.getConfSum() : 0;
            trend.add(new QualityAnalyticsDTO.TrendPoint(d, dayAi,
                    dayAi > 0 ? round2(dayConfSum / dayAi) : 0.0,
                    r != null ? r.getFeedbackUp() : 0,
                    r != null ? r.getFeedbackDown() : 0));
            if (r != null) {
                aiMsg += dayAi;
                confSum += dayConfSum;
                lowConf += r.getLowConfidenceCount();
                fallback += r.getFallbackCount();
                up += r.getFeedbackUp();
                down += r.getFeedbackDown();
                sessions += r.getSessionCount();
                handover += r.getHandoverCount();
                resolved += r.getResolvedCount();
                ended += r.getEndedCount();
            }
        }
        QualityAnalyticsDTO.Summary summary = new QualityAnalyticsDTO.Summary(
                aiMsg,
                aiMsg > 0 ? round2(confSum / aiMsg) : 0.0,
                aiMsg > 0 ? round1((double) lowConf / aiMsg * 100) : 0.0,
                aiMsg > 0 ? round1((double) fallback / aiMsg * 100) : 0.0,
                up, down,
                ended > 0 ? round1((double) resolved / ended * 100) : 0.0,
                sessions > 0 ? round1((double) handover / sessions * 100) : 0.0);
        return new QualityAnalyticsDTO(summary, trend, sc.platform() ? "PLATFORM" : "TENANT", cutoff);
    }

    /** 热点问题：关键词 TopN（rollup）+ 高频问句 TopN（实时） */
    public HotTopicsDTO getHotTopics(int days, Long appIdFilter) {
        int span = normalizeDays(days);
        LocalDate cutoff = LocalDate.now().minusDays(1);
        LocalDate from = cutoff.minusDays(span - 1L);
        Scope sc = resolveScope(appIdFilter);

        List<HotTopicsDTO.KeywordItem> keywords = hotKeywordRepository
                .topKeywords(from, sc.tenantId(), sc.appId(), PageRequest.of(0, 20)).stream()
                .map(r -> new HotTopicsDTO.KeywordItem(r.getKeyword(), r.getFreq()))
                .toList();
        List<HotTopicsDTO.QuestionItem> questions = messageRepository
                .topQuestions(from.atStartOfDay(), sc.tenantId(), sc.appId(), 10).stream()
                .map(r -> new HotTopicsDTO.QuestionItem(r.getContent(), r.getCnt()))
                .toList();
        return new HotTopicsDTO(keywords, questions, sc.platform() ? "PLATFORM" : "TENANT", cutoff);
    }

    /** 知识覆盖率：命中率趋势（读质量 rollup）+ 文档命中排行 + 零命中文档 */
    public CoverageAnalyticsDTO getCoverage(int days, Long appIdFilter) {
        int span = normalizeDays(days);
        LocalDate cutoff = LocalDate.now().minusDays(1);
        LocalDate from = cutoff.minusDays(span - 1L);
        Scope sc = resolveScope(appIdFilter);

        Map<LocalDate, QualityDailyRow> byDate = qualityRepository
                .aggregateDaily(from, sc.tenantId(), sc.appId()).stream()
                .collect(Collectors.toMap(QualityDailyRow::getDate, Function.identity(), (a, b) -> a));
        List<CoverageAnalyticsDTO.TrendPoint> trend = new ArrayList<>(span);
        long total = 0, hit = 0;
        for (int i = 0; i < span; i++) {
            LocalDate d = from.plusDays(i);
            QualityDailyRow r = byDate.get(d);
            long dayTotal = r != null ? r.getAiMsgCount() : 0;
            long dayHit = r != null ? r.getHitQuestionCount() : 0;
            trend.add(new CoverageAnalyticsDTO.TrendPoint(d, dayTotal, dayHit,
                    dayTotal > 0 ? round1((double) dayHit / dayTotal * 100) : 0.0));
            total += dayTotal;
            hit += dayHit;
        }

        List<DocHitRankRow> rank = docHitRepository.rankDocs(from, sc.tenantId(), sc.appId(),
                PageRequest.of(0, TOP_N));
        List<Long> rankIds = rank.stream().map(DocHitRankRow::getDocumentId)
                .filter(Objects::nonNull).toList();
        Map<Long, String> names = rankIds.isEmpty() ? Map.of()
                : documentRepository.findNamesByIds(rankIds).stream()
                        .collect(Collectors.toMap(DocIdNameRow::getId, DocIdNameRow::getName));
        List<CoverageAnalyticsDTO.DocHitItem> topDocs = rank.stream()
                .map(r -> new CoverageAnalyticsDTO.DocHitItem(r.getDocumentId(),
                        names.getOrDefault(r.getDocumentId(), "未知文档"), r.getHits()))
                .toList();

        List<CoverageAnalyticsDTO.DocItem> zeroHit = documentRepository
                .findZeroHitDocs(from, sc.tenantId(), sc.appId()).stream()
                .map(r -> new CoverageAnalyticsDTO.DocItem(r.getId(), r.getName()))
                .toList();

        CoverageAnalyticsDTO.Summary summary = new CoverageAnalyticsDTO.Summary(total, hit,
                total > 0 ? round1((double) hit / total * 100) : 0.0);
        return new CoverageAnalyticsDTO(summary, trend, topDocs, zeroHit,
                sc.platform() ? "PLATFORM" : "TENANT", cutoff);
    }

    /** 未命中提问明细（实时含当日；size 钳位 [1,100]） */
    public PageDTO<MissedQuestionDTO> getMisses(int days, Long appIdFilter, int page, int size) {
        int span = normalizeDays(days);
        LocalDateTime from = LocalDate.now().minusDays(span - 1L).atStartOfDay();
        Scope sc = resolveScope(appIdFilter);
        Page<MissedQuestionRow> rows = messageRepository.findMissedQuestions(
                from, sc.tenantId(), sc.appId(),
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100)));
        return PageDTO.from(rows.map(r ->
                new MissedQuestionDTO(r.getContent(), r.getCreatedAt(), r.getSessionId())));
    }

    /** 保留两位小数（置信度显示用） */
    private double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
