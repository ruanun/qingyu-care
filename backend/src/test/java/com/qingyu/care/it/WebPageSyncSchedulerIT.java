package com.qingyu.care.it;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.Document;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.enums.DocumentStatus;
import com.qingyu.care.enums.DocumentType;
import com.qingyu.care.enums.SyncInterval;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集成测试：调度器的到期查询在<b>无 TenantContext</b> 时必须真能查到文档。
 *
 * <p>这是本特性最隐蔽的失败模式——普通 JPA 查询会被 Hibernate @Filter 用 tenant_id = -1
 * 静默拒绝，任务照常跑、日志照常打，就是什么都不同步。单测 mock 掉 repository 永远抓不到，
 * 只有真实 Spring 上下文 + 真实数据库能验证。
 *
 * <p>⚠️ 本 IT 曾踩过一个自我欺骗的坑（变异自证 Step 7 抓到）：{@code TenantContext.clear()}
 * 只清 ThreadLocal，并不会主动禁用 Session 上已启用的 Hibernate Filter——
 * {@code TenantDataAspect} 在「无上下文」分支里，只要 {@code isLoggedIn()} 为 false
 * 就直接 return，从不动过滤器。同一 {@code @Transactional} 事务内，播种阶段
 * （有租户上下文）已把 tenantFilter 设成了 tenantId=1，clear 之后这个过滤器原样留在
 * Session 上——而种子文档恰好也是 tenant_id=1，于是普通 JPQL 会"蒙对"，把变异测试
 * 骗过去。真正对应生产的风险分支是 {@code TenantDataAspect} 第 3 支：「已登录非管理员
 * 却无租户上下文」时会把 tenantFilter 强制设为 -1L（拒绝一切）。所以这里手动把
 * Session 上的过滤器显式设成 -1L，复现该分支的真实效果，让原生查询"绕过拒一切的过滤器"
 * 这件事真正被断言到、也真正能被变异测试捕捉到。
 *
 * @author ruan
 */
@Transactional
class WebPageSyncSchedulerIT extends AbstractPostgresIT {

    @Autowired DocumentRepository documentRepository;
    @Autowired KnowledgeBaseRepository knowledgeBaseRepository;
    @Autowired EntityManager entityManager;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /**
     * 显式复现 {@code TenantDataAspect} 第 3 支（已登录非管理员却无租户上下文）的效果：
     * tenantFilter 强制设为 -1L，拒绝一切租户。仅调用 {@code TenantContext.clear()}
     * 不够——它不会禁用 Session 上已启用的过滤器，留下的陈旧过滤器可能恰好还放行种子数据，
     * 使测试"蒙对"而非真的验证了绕过逻辑（见类注释）。
     */
    private void simulateNoContextRejectAllFilter() {
        Session session = entityManager.unwrap(Session.class);
        session.enableFilter("tenantFilter").setParameter("tenantId", -1L);
    }

    @Test
    void 无租户上下文时到期查询仍能查到网页文档() {
        // 播种：先设上下文建数据（实体经 TenantEntityListener 回填 tenant_id/app_id）
        TenantContext.setTenantId(1L);
        TenantContext.setAppId(1L);
        KnowledgeBase kb = knowledgeBaseRepository.save(
                KnowledgeBase.builder().name("网页同步 IT 知识库").enabled(true).build());
        Document doc = documentRepository.save(Document.builder()
                .knowledgeBase(kb)
                .name("https://example.com/faq")
                .documentType(DocumentType.URL)
                .status(DocumentStatus.COMPLETED)
                .sourceUrl("https://example.com/faq")
                .syncInterval(SyncInterval.DAILY)
                .lastSyncedAt(LocalDateTime.now().minusDays(2))   // 两天前同步过，已到期
                .build());

        // 关键：清掉上下文，模拟调度器线程；并显式复现"拒绝一切"过滤器状态（见类注释）
        TenantContext.clear();
        simulateNoContextRejectAllFilter();

        List<Long> dueIds = documentRepository.findDueWebPageIds(LocalDateTime.now());

        assertThat(dueIds).contains(doc.getId());
    }

    @Test
    void MANUAL_周期的文档不会被查出() {
        TenantContext.setTenantId(1L);
        TenantContext.setAppId(1L);
        KnowledgeBase kb = knowledgeBaseRepository.save(
                KnowledgeBase.builder().name("网页同步 IT 知识库2").enabled(true).build());
        Document manual = documentRepository.save(Document.builder()
                .knowledgeBase(kb)
                .name("https://example.com/about")
                .documentType(DocumentType.URL)
                .status(DocumentStatus.COMPLETED)
                .sourceUrl("https://example.com/about")
                .syncInterval(SyncInterval.MANUAL)
                .lastSyncedAt(LocalDateTime.now().minusYears(1))
                .build());
        TenantContext.clear();
        simulateNoContextRejectAllFilter();

        assertThat(documentRepository.findDueWebPageIds(LocalDateTime.now()))
                .doesNotContain(manual.getId());
    }
}
