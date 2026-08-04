package com.qingyu.care.service.web;

import com.qingyu.care.entity.Document;
import com.qingyu.care.enums.DocumentStatus;
import com.qingyu.care.enums.SyncInterval;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.service.KnowledgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单元测试：网页同步的三条分支——内容未变不重嵌、变了才重嵌、失败保留旧内容；
 * 以及重嵌本身失败时 contentHash 不落库（否则下一轮同步会误判"内容未变"永久跳过重试）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class WebPageSyncServiceTest {

    @Mock DocumentRepository documentRepository;
    @Mock KnowledgeService knowledgeService;
    @Mock WebPageFetcher webPageFetcher;
    @InjectMocks WebPageSyncService service;

    /**
     * 默认「抢到同步互斥锁」。lenient 是因为 addWebPage 那条用例走不到 syncDocument，
     * 严格模式会因未消费的打桩报 UnnecessaryStubbingException。
     */
    @BeforeEach
    void 默认抢到同步锁() {
        lenient().when(documentRepository.tryLockWebPageSync(anyLong())).thenReturn(true);
    }

    private Document urlDoc(String hash) {
        Document doc = Document.builder()
                .sourceUrl("https://example.com/faq")
                .contentHash(hash)
                .content("旧正文")
                .syncInterval(SyncInterval.DAILY)
                .syncFailureCount(3)
                .build();
        doc.setId(1L);
        return doc;
    }

    @Test
    void addWebPage时知识库不属于当前租户则拒绝且不建档() {
        // 归属校验必须经 knowledgeService.getKnowledgeBase(...) 完成——它内部做
        // TenantOwnershipGuard 校验；越权 id 时真实实现会抛异常，这里用同语义的
        // RuntimeException 模拟。回归防线：曾经直接用 knowledgeBaseRepository.findById
        // 绕过了这层校验，导致任意租户能把网页文档插进别人的知识库（Critical 越权）
        when(knowledgeService.getKnowledgeBase(99L))
                .thenThrow(new RuntimeException("Knowledge base not found: 99"));

        assertThrows(RuntimeException.class,
                () -> service.addWebPage(99L, "https://example.com/faq", SyncInterval.DAILY));

        // 显式钉住"取知识库必须经过这条有归属校验的路径"——不然本测试的判别力全靠
        // MockitoExtension 的 STRICT_STUBS 隐式兜底（换回裸 findById 时，上面的 stub
        // 因未被消费而报 UnnecessaryStubbingException，测试碰巧也会红）。哪天有人给
        // 这条测试加 lenient() 或调整了 strictness 配置，隐式判别力会悄悄失效，
        // 这行显式 verify 是不依赖任何 Mockito 配置的独立断言
        verify(knowledgeService).getKnowledgeBase(99L);
        verify(documentRepository, never()).save(any());
    }

    @Test
    void 内容未变时不重嵌只更新同步时间() {
        Document doc = urlDoc("HASH_A");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(webPageFetcher.fetch(anyString()))
                .thenReturn(new WebPageFetcher.FetchResult("标题", "新抓到的正文", "HASH_A", 200));

        service.syncDocument(1L);

        // 这条是本特性最容易悄悄退化的地方：退化成「每次都重嵌」时功能完全正常，
        // 只是每轮同步都白烧一次 embedding 的钱，没有任何现象会提醒
        verify(knowledgeService, never()).processDocument(anyLong());
        assertThat(doc.getContent()).isEqualTo("旧正文");   // 未被覆盖
        assertThat(doc.getLastSyncedAt()).isNotNull();
        assertThat(doc.getSyncFailureCount()).isZero();     // 成功清零
    }

    @Test
    void 内容变了才重嵌并更新正文与哈希() {
        Document doc = urlDoc("HASH_A");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(webPageFetcher.fetch(anyString()))
                .thenReturn(new WebPageFetcher.FetchResult("新标题", "改版后的正文", "HASH_B", 200));
        // knowledgeService.processDocument 是同步调用，与 syncDocument 共享同一个 doc 托管实例，
        // 真实实现会直接在这同一个对象上把状态改成 COMPLETED——用 doAnswer 模拟这一效果
        doAnswer(inv -> {
            doc.setStatus(DocumentStatus.COMPLETED);
            return null;
        }).when(knowledgeService).processDocument(1L);

        service.syncDocument(1L);

        verify(knowledgeService).processDocument(1L);
        assertThat(doc.getContent()).isEqualTo("改版后的正文");
        assertThat(doc.getContentHash()).isEqualTo("HASH_B");
        assertThat(doc.getName()).isEqualTo("新标题");
        assertThat(doc.getSyncFailureCount()).isZero();
    }

    @Test
    void 重嵌失败时contentHash不落库_下一轮仍判定为内容有变() {
        Document doc = urlDoc("HASH_A");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(webPageFetcher.fetch(anyString()))
                .thenReturn(new WebPageFetcher.FetchResult("新标题", "改版后的正文", "HASH_B", 200));
        // 模拟 processDocument 内部吞异常后落 FAILED + 写入真实原因（真实实现从不重抛，
        // 只能靠状态判断成败；errorMessage 是它在同一持久化上下文里留下的诊断信息）
        doAnswer(inv -> {
            doc.setStatus(DocumentStatus.FAILED);
            doc.setErrorMessage("Tika 解析失败：不支持的文档结构");
            return null;
        }).when(knowledgeService).processDocument(1L);

        service.syncDocument(1L);

        // 这是本次修复的核心断言：contentHash 必须仍是旧值 HASH_A，
        // 这样下一轮同步 result.contentHash()（还会是 HASH_B）与它比对不相等，
        // 会再次判定"内容有变"并重试——而不是被永久误判为"已处理，跳过"
        assertThat(doc.getContentHash()).isEqualTo("HASH_A");
        assertThat(doc.getContentHash()).isNotEqualTo("HASH_B");
        // 重嵌失败也要计入失败计数、接入 MAX_FAILURES 熔断——否则永远嵌不进去的网页
        // 会被每小时重试到天荒地老，永远碰不到自动转手动
        assertThat(doc.getSyncFailureCount()).isEqualTo(4);   // 3 → 4
        // 必须保留 processDocument 写的真实原因，而不是被通用文案"重新嵌入失败"冲掉——
        // 冲掉了运营排障就只能去翻服务器日志（评审 round 2 收尾发现的退化）
        assertThat(doc.getErrorMessage()).contains("Tika 解析失败：不支持的文档结构");
    }

    @Test
    void 重嵌失败但processDocument未写errorMessage时回退通用文案() {
        Document doc = urlDoc("HASH_A");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(webPageFetcher.fetch(anyString()))
                .thenReturn(new WebPageFetcher.FetchResult("新标题", "改版后的正文", "HASH_B", 200));
        // 边界情况：processDocument 落 FAILED 但没写 errorMessage（如 e.getMessage() 本身为 null）
        doAnswer(inv -> {
            doc.setStatus(DocumentStatus.FAILED);
            return null;
        }).when(knowledgeService).processDocument(1L);

        service.syncDocument(1L);

        assertThat(doc.getErrorMessage()).contains("重新嵌入失败");
    }

    @Test
    void 抓取总成功但重嵌总失败时失败计数持续累积不会卡在1() {
        // 回归本次修复前的一个真实陷阱：若在"抓取成功"处就立刻把 syncFailureCount 清零，
        // 那么"抓取总成功、重嵌总失败"这种场景会让计数每轮都被重置后只加到 1，永远到不了
        // MAX_FAILURES，熔断形同虚设——必须等本轮真正确认成功（内容未变 / 重嵌完成）才清零
        Document doc = urlDoc("HASH_A");
        doc.setSyncFailureCount(0);
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(webPageFetcher.fetch(anyString()))
                .thenReturn(new WebPageFetcher.FetchResult("新标题", "改版后的正文", "HASH_B", 200));
        doAnswer(inv -> {
            doc.setStatus(DocumentStatus.FAILED);
            return null;
        }).when(knowledgeService).processDocument(1L);

        service.syncDocument(1L);
        assertThat(doc.getSyncFailureCount()).isEqualTo(1);

        service.syncDocument(1L);   // 第二轮：抓取依旧成功（HASH_B 不变），重嵌依旧失败
        assertThat(doc.getSyncFailureCount()).isEqualTo(2);   // 而不是被"抓取成功"重置回 1
    }

    @Test
    void 抓取失败时保留旧内容与旧哈希只累加失败计数() {
        Document doc = urlDoc("HASH_A");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(webPageFetcher.fetch(anyString())).thenThrow(new BusinessException("网页抓取失败: 502"));

        service.syncDocument(1L);   // 不应抛穿

        verify(knowledgeService, never()).processDocument(anyLong());
        assertThat(doc.getContent()).isEqualTo("旧正文");      // 旧内容仍在服务
        assertThat(doc.getContentHash()).isEqualTo("HASH_A");
        assertThat(doc.getSyncFailureCount()).isEqualTo(4);    // 3 → 4
        assertThat(doc.getErrorMessage()).contains("502");
        assertThat(doc.getLastSyncedAt()).isNull();            // 失败不更新成功时间
    }

    @Test
    void 连续失败十次后自动转为手动同步() {
        Document doc = urlDoc("HASH_A");
        doc.setSyncFailureCount(9);
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(webPageFetcher.fetch(anyString())).thenThrow(new BusinessException("404"));

        service.syncDocument(1L);

        assertThat(doc.getSyncFailureCount()).isEqualTo(10);
        assertThat(doc.getSyncInterval()).isEqualTo(SyncInterval.MANUAL);
        assertThat(doc.getErrorMessage()).contains("已停止自动同步");
    }

    @Test
    void 失败计数为null的存量行按0处理不NPE() {
        Document doc = urlDoc("HASH_A");
        doc.setSyncFailureCount(null);
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(webPageFetcher.fetch(anyString())).thenThrow(new BusinessException("超时"));

        service.syncDocument(1L);

        assertThat(doc.getSyncFailureCount()).isEqualTo(1);
    }

    @Test
    void 非URL类型的文档拒绝同步() {
        Document doc = Document.builder().sourceUrl(null).build();
        doc.setId(2L);
        when(documentRepository.findById(2L)).thenReturn(Optional.of(doc));

        service.syncDocument(2L);

        // 注意：sourceUrl 是 null，anyString() 不匹配 null 调用，必须用 any()，
        // 否则删掉守卫后 fetch(null) 照样能通过 never() 断言，测试形同虚设
        verify(webPageFetcher, never()).fetch(any());
        verify(knowledgeService, never()).processDocument(anyLong());
    }

    @Test
    void 抢不到同步锁时直接返回_不重复抓取也不重复嵌入() {
        // 同一文档并发同步会产出重复向量：A 删向量（未提交）→ B 的 DELETE 在自己的语句
        // 快照下看不到 A 刚插入的行、删 0 行 → 各插一份，vector_store 出现 2N 行而
        // chunk_count 只记 N。连点两次「立即重新抓取」就能触发
        when(documentRepository.tryLockWebPageSync(1L)).thenReturn(false);

        service.syncDocument(1L);

        verify(documentRepository, never()).findById(anyLong());
        verify(webPageFetcher, never()).fetch(any());
        verify(knowledgeService, never()).processDocument(anyLong());
        verify(documentRepository, never()).save(any());
    }
}
