package com.qingyu.care.service;

import com.qingyu.care.entity.Tenant;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.StorageUsageDao;
import com.qingyu.care.repository.SysApiUsageRepository;
import com.qingyu.care.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 租户级存储配额（安全审查 M5 的残留部分，2026-09-04 补齐）。
 *
 * <p>此前只有「每次」「每个」的上限：单文件 30MB / 20MB、单会话 20 个附件、孤儿 2 小时清理。
 * 没有任何一环管「这个租户一共占了多少」——攻击者可以不断建新会话把每个都灌满，而<b>已发送的
 * 附件不会被孤儿清理</b>，那是永久占用；租户自己往知识库灌几千份文档同样没人拦。
 *
 * <p>计量口径是<b>对象存储</b>：知识库文件 + 聊天附件的 {@code file_size} 之和。爬取产生的
 * URL 类文档 {@code file_size} 为 null（正文在库里、不占 S3），且页数已有 500 的硬顶，不纳入。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class QuotaServiceStorageTest {

    private static final long GB = 1024L * 1024 * 1024;

    @Mock ConfigService configService;
    @Mock TenantRepository tenantRepository;
    @Mock SysApiUsageRepository usageRepository;
    @Mock StorageUsageDao storageUsageDao;
    @InjectMocks QuotaService service;

    private void stubPlanAndLimit(TenantPlan plan, long limitBytes) {
        lenient().when(tenantRepository.findById(1L))
                .thenReturn(Optional.of(Tenant.builder().plan(plan).build()));
        lenient().when(configService.getLong(eq(OwnerScope.PLATFORM), isNull(),
                eq("billing.quota." + plan.name() + ".storage-bytes"), anyLong())).thenReturn(limitBytes);
    }

    @Test
    void FREE默认1GB_PRO默认20GB_ENTERPRISE不限() {
        // 内置默认值：配置缺失时 getLong 返回传入的兜底值，这里用它反查内置默认
        lenient().when(tenantRepository.findById(1L))
                .thenReturn(Optional.of(Tenant.builder().plan(TenantPlan.FREE).build()));
        when(configService.getLong(eq(OwnerScope.PLATFORM), isNull(),
                eq("billing.quota.FREE.storage-bytes"), anyLong()))
                .thenAnswer(inv -> inv.getArgument(3));
        assertThat(service.resolveStorageLimit(1L)).isEqualTo(GB);

        lenient().when(tenantRepository.findById(2L))
                .thenReturn(Optional.of(Tenant.builder().plan(TenantPlan.PRO).build()));
        when(configService.getLong(eq(OwnerScope.PLATFORM), isNull(),
                eq("billing.quota.PRO.storage-bytes"), anyLong()))
                .thenAnswer(inv -> inv.getArgument(3));
        assertThat(service.resolveStorageLimit(2L)).isEqualTo(20 * GB);

        lenient().when(tenantRepository.findById(3L))
                .thenReturn(Optional.of(Tenant.builder().plan(TenantPlan.ENTERPRISE).build()));
        when(configService.getLong(eq(OwnerScope.PLATFORM), isNull(),
                eq("billing.quota.ENTERPRISE.storage-bytes"), anyLong()))
                .thenAnswer(inv -> inv.getArgument(3));
        assertThat(service.resolveStorageLimit(3L)).isZero();   // 0 = 不限
    }

    /** 变异实验：把 requireStorageAvailable 里的比较删掉或改成只比已用量，本条转红。 */
    @Test
    void 加上本次上传会超限则拒绝() {
        stubPlanAndLimit(TenantPlan.FREE, 1000);
        when(storageUsageDao.sumBytes(1L)).thenReturn(900L);

        assertThatThrownBy(() -> service.requireStorageAvailable(1L, 200))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("存储");
    }

    @Test
    void 加上本次上传正好不超则放行() {
        stubPlanAndLimit(TenantPlan.FREE, 1000);
        when(storageUsageDao.sumBytes(1L)).thenReturn(900L);

        assertThatCode(() -> service.requireStorageAvailable(1L, 100)).doesNotThrowAnyException();
    }

    @Test
    void 上限为0表示不限_不查用量() {
        stubPlanAndLimit(TenantPlan.ENTERPRISE, 0);

        assertThatCode(() -> service.requireStorageAvailable(1L, Long.MAX_VALUE)).doesNotThrowAnyException();
        verify(storageUsageDao, never()).sumBytes(anyLong());
    }

    /** 平台管理员无租户上下文，不受配额约束。 */
    @Test
    void 无租户上下文时放行() {
        assertThatCode(() -> service.requireStorageAvailable(null, 999)).doesNotThrowAnyException();
        verify(storageUsageDao, never()).sumBytes(anyLong());
    }

    /**
     * 与既有额度检查同惯例：统计组件故障时 fail-open 放行，不因为算不出用量就挡住业务。
     * 变异实验：把 catch 去掉，本条会因 DAO 抛异常而转红。
     */
    @Test
    void 统计失败时failOpen放行() {
        stubPlanAndLimit(TenantPlan.FREE, 1000);
        when(storageUsageDao.sumBytes(1L)).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> service.requireStorageAvailable(1L, 200)).doesNotThrowAnyException();
    }
}
