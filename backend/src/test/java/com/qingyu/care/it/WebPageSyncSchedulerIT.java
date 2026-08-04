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
 * 集成测试：调度器的到期查询在 Hibernate 租户过滤器被<b>强制拒绝一切</b>（tenantFilter
 * 设为 -1L）时仍必须真能查到文档，验证原生 SQL 绕过过滤器这条纵深防御真的有效。
 *
 * <p>⚠️ 更正：这条 -1L「拒绝一切」并<b>不是</b>调度器线程在生产环境的真实状态——调度器
 * 线程没有 Sa-Token 会话，{@code TenantDataAspect} 在「未登录」分支直接 return、根本不会
 * 启用任何过滤器（-1L 那一支只在「已登录的非管理员却缺租户上下文」时才会触发，见该类
 * {@code enableFilters()} 方法）。选 native 是纵深防御，不依赖「调度器线程永远没有会话」
 * 这个前提本身——本 IT 就是构造这个前提不成立时的最坏情形（过滤器被强制拒绝一切），
 * 验证 native 依然不受影响。单测 mock 掉 repository 测不到这层 Hibernate/Session 交互，
 * 只有真实 Spring 上下文 + 真实数据库能验证。
 *
 * <p>⚠️ 本 IT 曾踩过一个自我欺骗的坑（变异自证 Step 7 抓到）：最初只写了
 * {@code TenantContext.clear()}，以为这样就"模拟了无上下文"，但它只清 ThreadLocal，
 * 并不会主动禁用 Session 上已启用的 Hibernate Filter。同一 {@code @Transactional}
 * 事务内，播种阶段（有租户上下文）已把 tenantFilter 设成了 tenantId=1，clear 之后这个
 * 过滤器原样留在 Session 上——而种子文档恰好也是 tenant_id=1，于是把 native 改成
 * 普通 JPQL 做变异测试时也会"蒙对"、测试仍然通过，把变异自证骗过去了。修法是像现在
 * 这样，用 {@link #simulateNoContextRejectAllFilter()} 手动把过滤器显式设成 -1L，
 * 构造一个确定性的、不依赖 Session 残留状态的「拒绝一切」场景，这样原生查询"绕过被拒的
 * 过滤器"这件事才真正被断言到、也真正能被变异测试捕捉到（详见 task-4-report.md）。
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
