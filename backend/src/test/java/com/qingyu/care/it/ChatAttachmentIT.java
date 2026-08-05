package com.qingyu.care.it;

import cn.dev33.satoken.secure.BCrypt;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.ChatAttachment;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.enums.TenantStatus;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.ChatAttachmentRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.service.FileService;
import com.qingyu.care.support.AbstractPostgresIT;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 附件上传与访问的端到端：两条上传端点（管理端 satoken 登录 / 开放 API appKey+secret）、
 * 两条访问端点的跨租户越权拒绝、开放 API 同 app 跨 session 越权拒绝、平台管理员上传件对
 * 普通租户不可读、上传时 sessionId 归属校验、{@code @CreationTimestamp} 与孤儿查询的端到端、
 * 下载响应头与响应体。
 *
 * <p>不加类级 {@code @Transactional}——种子数据须经真实 HTTP 请求流经拦截器链才可见
 * （同 AnalyticsUsageIT 的教训），结尾在 {@link #cleanupAttachments()} 手动清理。
 *
 * <p>{@link FileService} 用 {@code @MockitoBean} 打桩，不依赖本机真实 RustFS——之前用真实
 * S3 时，本地没跑 {@code docker compose up -d} 的机器会因连接超时而红、且 {@code @AfterEach}
 * 只删库行不删 S3 对象，每跑一次都往桶里留永久泄漏对象。本项目已有先例（知识库摄取全链路 IT
 * 因同样理由放弃），不为附件破例。
 *
 * @author ruan
 */
class ChatAttachmentIT extends AbstractPostgresIT {

    @Autowired MockMvc mockMvc;
    @Autowired ChatAttachmentRepository attachmentRepository;
    @Autowired ChatSessionRepository sessionRepository;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager entityManager;

    @MockitoBean
    FileService fileService;

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

    /** 播种一个属于给定 app 的真实会话，供上传端点的 sessionId 归属校验通过 */
    private ChatSession seedSession(String sessionId, Application app) {
        ChatSession session = new ChatSession();
        session.setSessionId(sessionId);
        session.setStatus(SessionStatus.AI_PROCESSING);
        session.setTenantId(app.getTenant().getId());
        session.setAppId(app.getId());
        return sessionRepository.saveAndFlush(session);
    }

    /** 无租户上下文播种一个会话（tenant_id/app_id 皆 NULL）——模拟平台管理员建档落 NULL 的既有行为 */
    private ChatSession seedPlatformSession(String sessionId) {
        ChatSession session = new ChatSession();
        session.setSessionId(sessionId);
        session.setStatus(SessionStatus.AI_PROCESSING);
        return sessionRepository.saveAndFlush(session);
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

    /** 打桩 FileService：uploadChatBytes 返回一个唯一 key，openStream(key) 回放给定字节 */
    private String stubStorage(byte[] bytes) {
        String key = "mock-key-" + System.nanoTime();
        when(fileService.uploadChatBytes(any(), any(), any())).thenReturn(key);
        when(fileService.openStream(key)).thenReturn(new ByteArrayInputStream(bytes));
        return key;
    }

    // ===== 管理端上传 =====

    @Test
    void 上传图片返回带宽高的附件信息() throws Exception {
        Actor actor = seedActor("up" + System.nanoTime());
        seedSession("it-sess-1", actor.app());
        byte[] bytes = png();
        stubStorage(bytes);
        MockMultipartFile file = new MockMultipartFile("file", "截图.png", "image/png", bytes);

        MvcResult result = mockMvc.perform(multipart("/v1/chat/files")
                        .file(file)
                        .param("sessionId", "it-sess-1")
                        .header("satoken", actor.token())
                        .header("X-App-Id", String.valueOf(actor.app().getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.width").value(10))
                .andExpect(jsonPath("$.data.height").value(10))
                .andExpect(jsonPath("$.data.contentType").value("image/png"))
                .andExpect(jsonPath("$.data.url").doesNotExist()) // I-3：不回拼好的 url，两端鉴权路径不同
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
        seedSession("it-sess-bad", actor.app());
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

    // ===== I-2：上传时 sessionId 归属校验 =====

    @Test
    void 上传时sessionId属于其他租户则拒绝且不落库() throws Exception {
        Actor victim = seedActor("supld-v" + System.nanoTime());
        Actor attacker = seedActor("supld-a" + System.nanoTime());
        ChatSession victimSession = seedSession("victim-sess-" + System.nanoTime(), victim.app());
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", png());

        // 攻击者自己合法登录，但把上传绑定到受害者的会话 id 上
        mockMvc.perform(multipart("/v1/chat/files")
                        .file(file)
                        .param("sessionId", victimSession.getSessionId())
                        .header("satoken", attacker.token())
                        .header("X-App-Id", String.valueOf(attacker.app().getId())))
                .andExpect(status().isNotFound());

        assertThat(attachmentRepository.findAll().stream()
                .anyMatch(a -> victimSession.getSessionId().equals(a.getSessionId())))
                .isFalse();
    }

    // ===== A. 跨租户访问被拒（两条访问端点分别验证）=====

    @Test
    void 管理端跨租户下载附件被拒_归属方本人可读() throws Exception {
        Actor victim = seedActor("mvic" + System.nanoTime());
        Actor attacker = seedActor("matk" + System.nanoTime());
        String sessionId = "mvic-sess-" + System.nanoTime();
        seedSession(sessionId, victim.app());
        byte[] bytes = png();
        stubStorage(bytes);
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", bytes);

        MvcResult upload = mockMvc.perform(multipart("/v1/chat/files")
                        .file(file).param("sessionId", sessionId)
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
        mockMvc.perform(asyncDispatch(ownerRead))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes)); // I-4：确认端点真把内容原样流出去了
    }

    @Test
    void 开放API跨租户下载附件被拒_归属方本人可读() throws Exception {
        Application victim = seedApp("ovic" + System.nanoTime(), "http://ovic.example.com");
        Application attacker = seedApp("oatk" + System.nanoTime(), "http://oatk.example.com");
        ChatSession victimSession = seedSession("ovic-sess-" + System.nanoTime(), victim);
        byte[] bytes = png();
        stubStorage(bytes);
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", bytes);

        MvcResult upload = mockMvc.perform(multipart("/open/v1/chat/files")
                        .file(file).param("sessionId", victimSession.getSessionId())
                        .header("X-App-Id", victim.getAppKey())
                        .header("X-Api-Key", victim.getAppSecret()))
                .andExpect(status().isOk()).andReturn();
        long id = om.readTree(upload.getResponse().getContentAsString()).path("id").asLong();
        cleanupIds.add(id);

        // 攻击者持自己合法 app 凭据 + 自己编的 sessionId（跨 app，天然凑不出受害者的会话）
        mockMvc.perform(get("/open/v1/chat/files/{id}", id)
                        .param("sessionId", "attacker-guess")
                        .header("X-App-Id", attacker.getAppKey())
                        .header("X-Api-Key", attacker.getAppSecret()))
                .andExpect(status().isNotFound());

        MvcResult ownerRead = mockMvc.perform(get("/open/v1/chat/files/{id}", id)
                        .param("sessionId", victimSession.getSessionId())
                        .header("X-App-Id", victim.getAppKey())
                        .header("X-Api-Key", victim.getAppSecret()))
                .andExpect(request().asyncStarted()).andReturn();
        mockMvc.perform(asyncDispatch(ownerRead))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes));
    }

    // ===== C-1：开放 API 同一 app 内跨 session 枚举被拒（核心漏洞场景）=====

    /**
     * 开放 API 的鉴权粒度是「整个 app」，appKey 本身是公开信息（Widget 源码里写死）；
     * 若访问端点只校验 tenant/app 归属，同一 app 下任意访客（甚至只知道公开 appKey、
     * 没有任何会话的人）都能靠全局自增的附件 id 枚举出该 app 下所有人上传的文件。
     * 必须证明：即使拿到了合法的同 app 凭据，传错 sessionId 依然读不到。
     */
    @Test
    void 开放API同app不同session下载被拒_真正session可读() throws Exception {
        Application app = seedApp("csame" + System.nanoTime(), "http://csame.example.com");
        ChatSession sessionA = seedSession("open-sess-a-" + System.nanoTime(), app);
        ChatSession sessionB = seedSession("open-sess-b-" + System.nanoTime(), app);
        byte[] bytes = png();
        stubStorage(bytes);
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", bytes);

        MvcResult upload = mockMvc.perform(multipart("/open/v1/chat/files")
                        .file(file).param("sessionId", sessionA.getSessionId())
                        .header("X-App-Id", app.getAppKey())
                        .header("X-Api-Key", app.getAppSecret()))
                .andExpect(status().isOk()).andReturn();
        long id = om.readTree(upload.getResponse().getContentAsString()).path("id").asLong();
        cleanupIds.add(id);

        // 同一 app 的合法凭据，但传的是另一个真实存在的会话 sessionId —— 必须被拒
        mockMvc.perform(get("/open/v1/chat/files/{id}", id)
                        .param("sessionId", sessionB.getSessionId())
                        .header("X-App-Id", app.getAppKey())
                        .header("X-Api-Key", app.getAppSecret()))
                .andExpect(status().isNotFound());

        // 真正上传时用的 sessionId 才能读到
        MvcResult ownerRead = mockMvc.perform(get("/open/v1/chat/files/{id}", id)
                        .param("sessionId", sessionA.getSessionId())
                        .header("X-App-Id", app.getAppKey())
                        .header("X-Api-Key", app.getAppSecret()))
                .andExpect(request().asyncStarted()).andReturn();
        mockMvc.perform(asyncDispatch(ownerRead))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes));
    }

    // ===== I-1：平台管理员（NULL 租户）上传的附件对普通租户不可读 =====

    @Test
    void 平台管理员上传的附件对普通租户不可读_平台上下文可读() throws Exception {
        ChatSession platformSession = seedPlatformSession("platform-sess-" + System.nanoTime());
        String adminToken = login("admin", "admin123");
        byte[] bytes = png();
        stubStorage(bytes);
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", bytes);

        MvcResult upload = mockMvc.perform(multipart("/v1/chat/files")
                        .file(file).param("sessionId", platformSession.getSessionId())
                        .header("satoken", adminToken))
                .andExpect(status().isOk()).andReturn();
        long id = om.readTree(upload.getResponse().getContentAsString()).path("data").path("id").asLong();
        cleanupIds.add(id);
        assertThat(attachmentRepository.findById(id).orElseThrow().getTenantId()).isNull();

        Actor tenantB = seedActor("platb" + System.nanoTime());
        // 只要不带 X-App-Id（ctxAppId=null），旧的 belongsToCurrentContext 对 NULL 租户
        // 无条件放行；新的 mutableInCurrentContext 要求 ctxTenantId 非空时实体租户必须相等
        mockMvc.perform(get("/v1/chat/files/{id}", id)
                        .header("satoken", tenantB.token())
                        .header("X-App-Id", String.valueOf(tenantB.app().getId())))
                .andExpect(status().isNotFound());

        // 平台管理员自己（ctxTenantId 恒为 null）仍可读
        MvcResult adminRead = mockMvc.perform(get("/v1/chat/files/{id}", id).header("satoken", adminToken))
                .andExpect(request().asyncStarted()).andReturn();
        mockMvc.perform(asyncDispatch(adminRead))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes));
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
        assertThat(fresh.getCreatedAt()).isCloseTo(LocalDateTime.now(), within(5, ChronoUnit.MINUTES));

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

    // ===== C. 非图片响应头：attachment + nosniff；图片：inline + nosniff（含响应体）=====

    @Test
    void 文本文件下载为attachment图片下载为inline_均带nosniff且内容一致() throws Exception {
        Actor actor = seedActor("hdr" + System.nanoTime());
        String sessionId = "hdr-sess-" + System.nanoTime();
        seedSession(sessionId, actor.app());

        byte[] txtBytes = "hello world".getBytes(StandardCharsets.UTF_8);
        stubStorage(txtBytes);
        MockMultipartFile txt = new MockMultipartFile("file", "notes.txt", "text/plain", txtBytes);
        MvcResult txtUpload = mockMvc.perform(multipart("/v1/chat/files")
                        .file(txt).param("sessionId", sessionId)
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
                .andExpect(header().string("Content-Disposition", startsWith("attachment;")))
                .andExpect(content().string("hello world")); // I-4：不是只有头对了，内容也得原样吐出来

        byte[] pngBytes = png();
        stubStorage(pngBytes);
        MockMultipartFile img = new MockMultipartFile("file", "a.png", "image/png", pngBytes);
        MvcResult imgUpload = mockMvc.perform(multipart("/v1/chat/files")
                        .file(img).param("sessionId", sessionId)
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
                .andExpect(header().string("Content-Disposition", startsWith("inline;")))
                .andExpect(content().bytes(pngBytes));
    }
}
