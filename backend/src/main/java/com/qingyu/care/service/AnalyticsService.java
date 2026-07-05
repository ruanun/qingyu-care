package com.qingyu.care.service;

import com.qingyu.care.dto.UsageAnalyticsDTO;
import com.qingyu.care.dto.UsageAnalyticsDTO.RankingItem;
import com.qingyu.care.dto.UsageAnalyticsDTO.Summary;
import com.qingyu.care.dto.UsageAnalyticsDTO.TrendPoint;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.repository.SysApiUsageRepository;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.repository.projection.SessionDailyRow;
import com.qingyu.care.repository.projection.SessionSummaryRow;
import com.qingyu.care.repository.projection.UsageDailyRow;
import com.qingyu.care.repository.projection.UsageRankRow;
import lombok.RequiredArgsConstructor;
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
}
