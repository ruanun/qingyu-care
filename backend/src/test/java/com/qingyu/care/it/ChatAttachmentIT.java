package com.qingyu.care.it;

import cn.dev33.satoken.secure.BCrypt;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.ChatAttachment;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.enums.TenantStatus;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.ChatAttachmentRepository;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 附件上传与访问的端到端：两条上传端点（管理端 satoken 登录 / 开放 API appKey+secret）、
 * 两条访问端点的跨租户越权拒绝、{@code @CreationTimestamp} 与孤儿查询的端到端、下载响应头。
 *
 * <p>不加类级 {@code @Transactional}——种子数据须经真实 HTTP 请求流经拦截器链才可见
 * （同 AnalyticsUsageIT 的教训），结尾在 {@link #cleanupAttachments()} 手动清理。
 *
 * @author ruan
 */
class ChatAttachmentIT extends AbstractPostgresIT {

    @Autowired MockMvc mockMvc;
    @Autowired ChatAttachmentRepository attachmentRepository;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager entityManager;

    private final ObjectMapper om = new ObjectMapper();
    private final List<Long> cleanupIds = new ArrayList<>();

    @AfterEach
    void cleanupAttachments() {
        cleanupIds.forEach(id -> attachmentRepository.findById(id).ifPresent(attachmentRepository::delete));
        cleanupIds.clear();
    }

    private static byte[] png() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private String login(String username, String password) throws Exception {
        MvcResult r = mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString()).path("data").path("token").asString();
    }

    /** 管理端鉴权用：租户 + 应用 + 已登录成员，代表一个可访问管理端附件端点的操作者 */
    private record Actor(Application app, String token) {
    }

    private Actor seedActor(String suffix) throws Exception {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .name("附件IT租户-" + suffix).code("ATT_IT_" + suffix)
                .status(TenantStatus.ACTIVE).plan(TenantPlan.FREE).build());
        Application app = new Application();
        app.setName("附件IT应用-" + suffix);
        app.setTenant(tenant);
        app.setAppKey("att_it_" + suffix);
        app.setAppSecret("sec_" + suffix);
        app.setEnabled(true);
        app = applicationRepository.save(app);
        String username = "att_it_user_" + suffix;
        userRepository.save(User.builder()
                .username(username).password(BCrypt.hashpw("pw123456"))
                .nickname("附件IT用户").role(UserRole.TENANT_MEMBER).tenant(tenant).enabled(true).build());
        String token = login(username, "pw123456");
        return new Actor(app, token);
    }

    private void ownBy(ChatAttachment entity, Application app) {
        entity.setTenantId(app.getTenant().getId());
        entity.setAppId(app.getId());
    }

    private void backdateCreatedAt(Long id, LocalDateTime time) {
        entityManager.createNativeQuery("UPDATE chat_attachment SET created_at = ?1 WHERE id = ?2")
                .setParameter(1, time)
                .setParameter(2, id)
                .executeUpdate();
    }

    // ===== 管理端上传 =====

    @Test
    void 上传图片返回带宽高的附件信息() throws Exception {
        Actor actor = seedActor("up" + System.nanoTime());
        MockMultipartFile file = new MockMultipartFile("file", "截图.png", "image/png", png());

        MvcResult result = mockMvc.perform(multipart("/v1/chat/files")
                        .file(file)
                        .param("sessionId", "it-sess-1")
                        .header("satoken", actor.token())
                        .header("X-App-Id", String.valueOf(actor.app().getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.width").value(10))
                .andExpect(jsonPath("$.data.height").value(10))
                .andExpect(jsonPath("$.data.contentType").value("image/png"))
                .andExpect(jsonPath("$.data.url").value(startsWith("/v1/chat/files/")))
                .andReturn();

        long id = om.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
        cleanupIds.add(id);
        ChatAttachment saved = attachmentRepository.findById(id).orElseThrow();
        // 落库 tenantId/appId 须取自登录态 + X-App-Id，而非请求体（TenantEntityListener 回填）
        assertThat(saved.getTenantId()).isEqualTo(actor.app().getTenant().getId());
        assertThat(saved.getAppId()).isEqualTo(actor.app().getId());
    }

    @Test
    void 可执行文件改名成png被拒且不落库() throws Exception {
        Actor actor = seedActor("bad" + System.nanoTime());
        MockMultipartFile fake = new MockMultipartFile(
                "file", "photo.png", "image/png", new byte[]{0x4D, 0x5A, (byte) 0x90, 0x00});

        mockMvc.perform(multipart("/v1/chat/files")
                        .file(fake)
                        .param("sessionId", "it-sess-bad")
                        .header("satoken", actor.token())
                        .header("X-App-Id", String.valueOf(actor.app().getId())))
                .andExpect(status().isOk())   // 本项目 BusinessException 走 HTTP 200 + code 400
                .andExpect(jsonPath("$.code").value(400));

        assertThat(attachmentRepository.findAll().stream()
                .anyMatch(a -> "it-sess-bad".equals(a.getSessionId())))
                .isFalse();
    }

    // ===== A. 跨租户访问被拒（两条访问端点分别验证）=====

    @Test
    void 管理端跨租户下载附件被拒_归属方本人可读() throws Exception {
        Actor victim = seedActor("mvic" + System.nanoTime());
        Actor attacker = seedActor("matk" + System.nanoTime());
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", png());

        MvcResult upload = mockMvc.perform(multipart("/v1/chat/files")
                        .file(file).param("sessionId", "s")
                        .header("satoken", victim.token())
                        .header("X-App-Id", String.valueOf(victim.app().getId())))
                .andExpect(status().isOk()).andReturn();
        long id = om.readTree(upload.getResponse().getContentAsString()).path("data").path("id").asLong();
        cleanupIds.add(id);

        // 攻击者持自己合法登录态 + 合法 X-App-Id，只是拿到了受害者的附件 id
        mockMvc.perform(get("/v1/chat/files/{id}", id)
                        .header("satoken", attacker.token())
                        .header("X-App-Id", String.valueOf(attacker.app().getId())))
                .andExpect(status().isNotFound());

        MvcResult ownerRead = mockMvc.perform(get("/v1/chat/files/{id}", id)
                        .header("satoken", victim.token())
                        .header("X-App-Id", String.valueOf(victim.app().getId())))
                .andExpect(request().asyncStarted()).andReturn();
        mockMvc.perform(asyncDispatch(ownerRead)).andExpect(status().isOk());
    }

    @Test
    void 开放API跨租户下载附件被拒_归属方本人可读() throws Exception {
        Application victim = seedApp("ovic" + System.nanoTime(), "http://ovic.example.com");
        Application attacker = seedApp("oatk" + System.nanoTime(), "http://oatk.example.com");
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", png());

        MvcResult upload = mockMvc.perform(multipart("/open/v1/chat/files")
                        .file(file).param("sessionId", "s")
                        .header("X-App-Id", victim.getAppKey())
                        .header("X-Api-Key", victim.getAppSecret()))
                .andExpect(status().isOk()).andReturn();
        long id = om.readTree(upload.getResponse().getContentAsString()).path("id").asLong();
        cleanupIds.add(id);

        mockMvc.perform(get("/open/v1/chat/files/{id}", id)
                        .header("X-App-Id", attacker.getAppKey())
                        .header("X-Api-Key", attacker.getAppSecret()))
                .andExpect(status().isNotFound());

        MvcResult ownerRead = mockMvc.perform(get("/open/v1/chat/files/{id}", id)
                        .header("X-App-Id", victim.getAppKey())
                        .header("X-Api-Key", victim.getAppSecret()))
                .andExpect(request().asyncStarted()).andReturn();
        mockMvc.perform(asyncDispatch(ownerRead)).andExpect(status().isOk());
    }

    // ===== B. @CreationTimestamp 与孤儿查询端到端 =====

    // 原生 SQL UPDATE 需要一个活跃事务；本方法不经 MockMvc，
    // 加 @Transactional 仅影响这一个测试（结束后自动回滚，等同清理）
    @Test
    @Transactional
    void 创建时间非空且孤儿查询命中回拨行_排除新鲜孤儿与已发送行() {
        Application app = seedApp("orphan" + System.nanoTime(), "http://orphan.example.com");

        // ①新鲜孤儿：刚创建，createdAt 应非空且约等于当前时刻
        ChatAttachment fresh = ChatAttachment.builder()
                .sessionId("s").fileName("fresh.png").fileSize(1L).contentType("image/png")
                .storageKey("k-fresh-" + System.nanoTime()).uploaderRole(MessageRole.USER).build();
        ownBy(fresh, app);
        fresh = attachmentRepository.saveAndFlush(fresh);
        cleanupIds.add(fresh.getId());
        assertThat(fresh.getCreatedAt()).isNotNull();
        assertThat(fresh.getCreatedAt()).isCloseTo(LocalDateTime.now(), org.assertj.core.api.Assertions
                .within(5, ChronoUnit.MINUTES));

        // ②过期孤儿：messageId 为 null 且 created_at 回拨到 48 小时前 —— 该被查出
        ChatAttachment staleOrphan = ChatAttachment.builder()
                .sessionId("s").fileName("stale-orphan.png").fileSize(1L).contentType("image/png")
                .storageKey("k-stale-orphan-" + System.nanoTime()).uploaderRole(MessageRole.USER).build();
        ownBy(staleOrphan, app);
        staleOrphan = attachmentRepository.saveAndFlush(staleOrphan);
        cleanupIds.add(staleOrphan.getId());
        backdateCreatedAt(staleOrphan.getId(), LocalDateTime.now().minusHours(48));

        // ③已发送的旧附件：created_at 同样很旧，但 messageId 非空 —— 不该被查出
        ChatAttachment staleSent = ChatAttachment.builder()
                .sessionId("s").fileName("stale-sent.png").fileSize(1L).contentType("image/png")
                .storageKey("k-stale-sent-" + System.nanoTime()).uploaderRole(MessageRole.USER)
                .messageId(999999L).build();
        ownBy(staleSent, app);
        staleSent = attachmentRepository.saveAndFlush(staleSent);
        cleanupIds.add(staleSent.getId());
        backdateCreatedAt(staleSent.getId(), LocalDateTime.now().minusHours(48));

        entityManager.clear(); // 丢弃一级缓存，确保下面查询读到的是原生 SQL 回拨后的真实值

        List<ChatAttachment> orphans = attachmentRepository.findByMessageIdIsNullAndCreatedAtBefore(
                LocalDateTime.now().minusHours(24), PageRequest.of(0, 200));
        List<Long> orphanIds = orphans.stream().map(ChatAttachment::getId).toList();

        assertThat(orphanIds).contains(staleOrphan.getId());
        assertThat(orphanIds).doesNotContain(fresh.getId(), staleSent.getId());
    }

    // ===== C. 非图片响应头：attachment + nosniff；图片：inline + nosniff =====

    @Test
    void 文本文件下载为attachment图片下载为inline_均带nosniff() throws Exception {
        Actor actor = seedActor("hdr" + System.nanoTime());

        MockMultipartFile txt = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "hello world".getBytes(StandardCharsets.UTF_8));
        MvcResult txtUpload = mockMvc.perform(multipart("/v1/chat/files")
                        .file(txt).param("sessionId", "s")
                        .header("satoken", actor.token())
                        .header("X-App-Id", String.valueOf(actor.app().getId())))
                .andExpect(status().isOk()).andReturn();
        long txtId = om.readTree(txtUpload.getResponse().getContentAsString()).path("data").path("id").asLong();
        cleanupIds.add(txtId);

        MvcResult txtDownloadStarted = mockMvc.perform(get("/v1/chat/files/{id}", txtId)
                        .header("satoken", actor.token())
                        .header("X-App-Id", String.valueOf(actor.app().getId())))
                .andExpect(request().asyncStarted()).andReturn();
        mockMvc.perform(asyncDispatch(txtDownloadStarted))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", startsWith("attachment;")));

        MockMultipartFile img = new MockMultipartFile("file", "a.png", "image/png", png());
        MvcResult imgUpload = mockMvc.perform(multipart("/v1/chat/files")
                        .file(img).param("sessionId", "s")
                        .header("satoken", actor.token())
                        .header("X-App-Id", String.valueOf(actor.app().getId())))
                .andExpect(status().isOk()).andReturn();
        long imgId = om.readTree(imgUpload.getResponse().getContentAsString()).path("data").path("id").asLong();
        cleanupIds.add(imgId);

        MvcResult imgDownloadStarted = mockMvc.perform(get("/v1/chat/files/{id}", imgId)
                        .header("satoken", actor.token())
                        .header("X-App-Id", String.valueOf(actor.app().getId())))
                .andExpect(request().asyncStarted()).andReturn();
        mockMvc.perform(asyncDispatch(imgDownloadStarted))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", startsWith("inline;")));
    }
}
