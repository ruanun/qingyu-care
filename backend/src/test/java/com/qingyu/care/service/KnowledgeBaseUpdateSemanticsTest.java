package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.KnowledgeBaseUpdateRequest;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.repository.CrawlSiteRepository;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.repository.QaPairRepository;
import com.qingyu.care.repository.VectorStoreRepository;
import com.qingyu.care.service.impl.KnowledgeServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import com.qingyu.care.service.embedding.KnowledgeVectorWriter;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 守护「更新知识库不得把没传的字段打回类默认值」。
 *
 * <p>修之前 {@code updateKnowledgeBase} 收的是建库、改库共用的 {@code KnowledgeBaseRequest}，
 * 而那个类的 {@code enabled} 写了 {@code = true} 初始化器 → Jackson 无参构造照样应用 →
 * {@code if (request.getEnabled() != null)} 恒真 → <b>改一次名字就把已停用的知识库重新
 * 启用了</b>。现已拆出无初始化器的 {@link KnowledgeBaseUpdateRequest}，且它<b>不含</b>
 * {@code enabled}（启用状态有两个专用端点）。
 *
 * <p>⚠️ <b>本类的判别力边界要说清楚</b>：{@code enabled} 既然已从 DTO 上移除，第一条测试
 * 现在是<b>由类型系统保证</b>的——把服务层那行 {@code setEnabled} 加回来根本编译不过。它
 * 因此不是「盯着一行赋值」的守护，而是把行为契约钉住，防的是<b>整体回退</b>（有人把签名
 * 换回 {@code KnowledgeBaseRequest}，本类会编译失败）。真正盯着「{@code enabled} 不许悄悄
 * 回到 DTO 里」的是 {@code UpdateRequestFieldCoverageTest} 的白名单反向断言——那里一旦有人
 * 把 {@code enabled} 加进 DTO，会明确报「白名单条目已过时」。两者分工，别把任一条当成全部。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KnowledgeBaseUpdateSemanticsTest {

    @Mock KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock DocumentRepository documentRepository;
    @Mock KnowledgeVectorWriter vectorWriter;
    @Mock FileService fileService;
    @Mock VectorStoreRepository vectorStoreRepository;
    @Mock QaPairRepository qaPairRepository;
    @Mock CrawlSiteRepository crawlSiteRepository;

    private static final Long KB_ID = 7L;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private KnowledgeServiceImpl service() {
        return new KnowledgeServiceImpl(knowledgeBaseRepository, documentRepository,
                vectorWriter, fileService, vectorStoreRepository,
                qaPairRepository, crawlSiteRepository);
    }

    /** 播种一个属于当前租户、且<b>已停用</b>的知识库。 */
    private KnowledgeBase seedDisabledKb() {
        TenantContext.setTenantId(1L);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(KB_ID);
        kb.setName("旧名字");
        kb.setEnabled(false);
        kb.setTenantId(1L);
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(kb));
        when(knowledgeBaseRepository.save(any(KnowledgeBase.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return kb;
    }

    @Test
    @DisplayName("改名字不得把已停用的知识库静默启用")
    void 改名字不改动启用状态() {
        KnowledgeBase kb = seedDisabledKb();

        KnowledgeBaseUpdateRequest request = new KnowledgeBaseUpdateRequest();
        request.setName("新名字");

        KnowledgeBase updated = service().updateKnowledgeBase(KB_ID, request);

        assertThat(updated.getName()).isEqualTo("新名字");
        assertThat(updated.getEnabled())
                .as("知识库的启用状态有 /{id}/toggle 与 /{id}/enabled 两个专用端点，"
                        + "改名字这条路径不该碰它——被打回 true 意味着运营停用一个库之后"
                        + "只要有人编辑一次名称，它就又对全部会话生效了")
                .isFalse();
        assertThat(kb.getEnabled()).isFalse();
    }

    @Test
    @DisplayName("描述不给就清空——本端点对 name/description 是全量覆盖语义")
    void 描述按全量语义覆盖() {
        KnowledgeBase kb = seedDisabledKb();
        kb.setDescription("旧描述");

        KnowledgeBaseUpdateRequest request = new KnowledgeBaseUpdateRequest();
        request.setName("新名字");   // description 不给

        KnowledgeBase updated = service().updateKnowledgeBase(KB_ID, request);

        // 与 enabled 不同，name/description 是这个端点的正题，前端编辑框整份提交，
        // 「不给就清空」是有意的全量语义。钉住它是为了让将来若要改成局部语义，
        // 必须是一个显式决定而不是顺手改。
        assertThat(updated.getDescription()).isNull();
    }
}
