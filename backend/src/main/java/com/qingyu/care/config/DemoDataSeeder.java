package com.qingyu.care.config;

import cn.dev33.satoken.secure.BCrypt;
import com.qingyu.care.dto.DocumentStatusDTO;
import com.qingyu.care.dto.KnowledgeBaseRequest;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.entity.Document;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.DocumentStatus;
import com.qingyu.care.enums.DocumentVisibility;
import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.enums.TenantStatus;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.repository.ChatStrategyRepository;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.service.KnowledgeService;
import com.qingyu.care.service.QaPairService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 演示数据播种：一条命令产出可截图、可试用的完整数据。
 *
 * <p><b>启动方式</b>：{@code mvn spring-boot:run -Dspring-boot.run.profiles=dev,demo}
 *
 * <p><b>为什么是 ApplicationRunner 而不是 @PostConstruct</b>：四个配置服务
 * （ChatStrategyService / HandoverRuleService / PromptTemplateService / ModelRouterService）
 * 在 {@code @PostConstruct} 里播种 {@code tenant_id IS NULL} 的平台默认行，演示数据要能继承
 * 它们就必须排在其后。{@code @PostConstruct} 之间的相对顺序取决于 bean 依赖图，是隐式且
 * 脆弱的；ApplicationRunner 在容器完全就绪后执行，顺序确定。
 *
 * <p><b>为什么是 @Profile 而不是配置开关</b>：profile 是编译期即可见的隔离边界，生产启动
 * 不激活 demo 时这个 bean 根本不会被创建，不存在「配置写错了往生产库塞演示数据」的可能。
 *
 * @author ruan
 */
@Slf4j
@Component
@Profile("demo")
@RequiredArgsConstructor
public class DemoDataSeeder implements ApplicationRunner {

    /** 幂等键：这个 code 的租户存在即认为演示数据已播种过 */
    static final String DEMO_TENANT_CODE = "demo-cloudboat";

    /**
     * 演示应用的 appKey/appSecret 固定而非随机——使用手册与 README 里的 Widget 嵌入代码
     * 才能直接写死它，读者照抄就能跑通。文档中须标注「演示专用，生产必须重新生成」。
     */
    static final String DEMO_APP_KEY = "demo-cloudboat-shop";
    static final String DEMO_APP_SECRET = "demo-secret-cloudboat-shop-0001";

    private static final String DEMO_PASSWORD = "demo123456";

    private final TenantRepository tenantRepository;
    private final ApplicationRepository applicationRepository;
    private final UserRepository userRepository;
    private final ChatStrategyRepository chatStrategyRepository;
    private final KnowledgeService knowledgeService;
    private final QaPairService qaPairService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (tenantRepository.findByCode(DEMO_TENANT_CODE).isPresent()) {
                log.info("演示数据已存在（租户 code={}），跳过播种", DEMO_TENANT_CODE);
                return;
            }
            log.info("开始播种演示数据...");
            seed();
            log.info("演示数据播种完成");
        } catch (Exception e) {
            // 演示数据是旁路不是主链路，播种失败不该让应用起不来
            log.error("演示数据播种失败（不影响应用启动）: {}", e.getMessage(), e);
        }
    }

    private void seed() {
        Tenant tenant = seedTenant();
        User admin = seedUser(tenant, "demo_admin", "云舟管理员", UserRole.TENANT_ADMIN);
        seedUser(tenant, "demo_agent", "云舟客服小舟", UserRole.TENANT_MEMBER);
        Application shopApp = seedApp(tenant, "云舟商城客服", DEMO_APP_KEY, DEMO_APP_SECRET,
                "您好！我是云舟商城的智能客服小舟，可以帮您查询订单、退换货政策、配送与积分规则～");
        seedApp(tenant, "云舟内部 IT 助手", "demo-cloudboat-it", "demo-secret-cloudboat-it-0001",
                "您好，我是云舟内部 IT 助手，可以帮您解答办公设备、账号权限与网络相关问题。");
        log.info("演示租户/应用/用户就绪 tenantId={} appId={} adminId={}",
                tenant.getId(), shopApp.getId(), admin.getId());

        // 建立租户上下文后再建知识库/文档/QA/会话——这些实体继承 BaseTenantEntity，
        // 靠 TenantEntityListener 从 TenantContext 回填 tenant_id/app_id。
        //
        // ⚠️ 不搬运的后果不是「对所有租户可见」，而是反过来「对任何租户都检索不到」：
        // KnowledgeServiceImpl.processDocument 在 tenantId 为 null 时把向量 metadata 写成
        // "tenantId": ""，而 RetrievalScope.toFilterExpression 对非 null 租户拼的是
        // tenantId == '<真实租户id>' 等值条件，空串永远匹配不上任何真实租户 id。
        // 表现为 embedding 的钱照花、检索 0 命中、全程无异常无日志。
        try {
            TenantContext.setTenantId(tenant.getId());
            TenantContext.setAppId(shopApp.getId());
            TenantContext.setUserId(admin.getId());
            TenantContext.setAccessLevel(DocumentVisibility.INTERNAL);

            seedStrategy();
            Long kbId = seedKnowledge();
            log.info("演示知识库就绪 knowledgeBaseId={}", kbId);
        } finally {
            // 启动线程会被后续请求复用，不清理即泄漏给下一个使用者
            TenantContext.clear();
        }
    }

    /**
     * 建租户级问答策略，覆盖平台默认的相似度阈值。
     *
     * <p><b>为什么演示数据非建这条不可</b>：平台默认阈值是 0.7，而实测当前 embedding 模型
     * （{@code qwen3.7-text-embedding}）下——
     *
     * <ul>
     *   <li>真正相关的命中落在 <b>0.57 ~ 0.73</b>（逐字命中标题的「七天无理由退货」才 0.7298，
     *       「订单满99元即可包邮吗」只有 0.5709）</li>
     *   <li>完全无关的问题（天气 / 写诗 / Python）落在 <b>0.25 ~ 0.30</b></li>
     * </ul>
     *
     * <p>0.7 恰好卡在相关命中那一带的顶端，几乎把所有真实相关的匹配都滤掉，演示时表现为
     * 「问什么都答不知道」。取 <b>0.45</b>——落在 0.30 与 0.57 之间那段干净空档的中央，
     * 噪声进不来、真命中不漏掉。
     *
     * <p>用租户级策略覆盖而不是改平台默认，是因为平台默认属于产品配置、改动影响所有租户，
     * 不该由演示数据代为决定；这条同时演示了产品的「租户自有配置 → 平台默认」多级回退。
     */
    private void seedStrategy() {
        chatStrategyRepository.save(ChatStrategy.builder()
                .name("云舟商城默认策略")
                .scenario("default")
                .topK(5)
                .similarityThreshold(0.45)
                .allowNoKnowledge(true)
                .fallbackMessage("抱歉，这个问题我暂时没有找到答案。您可以换个说法再问一次，或者输入「人工」转接客服。")
                .enabled(true)
                .isDefault(true)
                .build());
        log.info("演示租户级问答策略已就绪 similarityThreshold=0.45");
    }

    /**
     * 建知识库 + 4 篇文档 + 8 条 QA。
     *
     * <p>文档走真实的 {@code uploadDocument()} 而非直接构造实体：这样演示数据与真人操作
     * 产出的数据完全同构——S3 里真有对象、Tika 真解析过、切分块数与文件大小都是真的、
     * 向量是真的。截图里的文档列表因此不会露出破绽，且顺带验证了整条摄取链路可用。
     *
     * @return 知识库 id
     */
    private Long seedKnowledge() {
        KnowledgeBaseRequest req = new KnowledgeBaseRequest();
        req.setName("云舟商城帮助中心");
        req.setDescription("退换货、配送物流、会员积分、支付发票四大类客服知识");
        req.setEnabled(true);
        KnowledgeBase kb = knowledgeService.createKnowledgeBase(req);

        List<Long> docIds = new ArrayList<>();
        for (DemoDataContent.DemoDoc d : DemoDataContent.DOCUMENTS) {
            Document doc = knowledgeService.uploadDocument(kb.getId(),
                    new InMemoryMultipartFile(d.filename(), d.markdown().getBytes(StandardCharsets.UTF_8)));
            docIds.add(doc.getId());
            log.info("演示文档已提交摄取 id={} name={}", doc.getId(), doc.getName());
        }
        awaitDocumentsSettled(docIds, Duration.ofSeconds(120));

        for (DemoDataContent.Qa qa : DemoDataContent.QA_PAIRS) {
            qaPairService.create(kb.getId(), qa.question(), qa.answer());
        }
        log.info("演示 QA 问答对已嵌入 count={}", DemoDataContent.QA_PAIRS.size());

        return kb.getId();
    }

    /**
     * 等文档摄取落地。
     *
     * <p>{@code uploadDocument()} 自带 {@code @Transactional}，内部经 {@code AfterCommit}
     * 把 {@code processDocumentAsync} 推迟到事务提交之后投递，因此本方法返回时文档仍是
     * PENDING。不等的后果是截图时文档卡在「处理中」，且后续会话数据里的命中记录与实际
     * 向量对不上。
     *
     * <p>超时只警告不失败——演示数据不值得让启动流程卡死。
     */
    private void awaitDocumentsSettled(List<Long> docIds, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            List<DocumentStatusDTO> statuses = docIds.stream()
                    .map(knowledgeService::getDocumentStatus)
                    .toList();
            boolean settled = statuses.stream().allMatch(s ->
                    s.getStatus() == DocumentStatus.COMPLETED || s.getStatus() == DocumentStatus.FAILED);
            if (settled) {
                long ok = statuses.stream().filter(s -> s.getStatus() == DocumentStatus.COMPLETED).count();
                log.info("演示文档摄取完成 成功={}/{}", ok, docIds.size());
                return;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("演示文档摄取等待超时（{}s），截图前请确认文档状态", timeout.toSeconds());
    }

    /**
     * 极简 MultipartFile：把内存字节喂给 {@code uploadDocument()}，走与真人上传完全相同的
     * 路径。不用 spring-test 的 MockMultipartFile——那是 test scope，生产代码引不到。
     */
    private record InMemoryMultipartFile(String filename, byte[] content) implements MultipartFile {
        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return filename;
        }

        @Override
        public String getContentType() {
            return "text/markdown";
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() {
            return content;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(File dest) throws IOException {
            Files.write(dest.toPath(), content);
        }
    }

    private Tenant seedTenant() {
        return tenantRepository.save(Tenant.builder()
                .name("云舟科技")
                .code(DEMO_TENANT_CODE)
                .contactName("周云舟")
                .contactPhone("13800000000")
                .contactEmail("demo@cloudboat.example.com")
                .status(TenantStatus.ACTIVE)
                .plan(TenantPlan.PRO)
                .build());
    }

    private User seedUser(Tenant tenant, String username, String nickname, UserRole role) {
        return userRepository.save(User.builder()
                .tenant(tenant)
                .username(username)
                .password(BCrypt.hashpw(DEMO_PASSWORD))
                .nickname(nickname)
                .email(username + "@cloudboat.example.com")
                .role(role)
                .enabled(true)
                .build());
    }

    private Application seedApp(Tenant tenant, String name, String appKey, String appSecret,
                                String welcomeMessage) {
        return applicationRepository.save(Application.builder()
                .tenant(tenant)
                .name(name)
                .appKey(appKey)
                .appSecret(appSecret)
                .description(name + "（演示应用，appKey/appSecret 为固定演示值，生产必须重新生成）")
                .welcomeMessage(welcomeMessage)
                .themeColor("#6366f1")
                .enabled(true)
                .rateLimitPerMinute(60)
                .allowedOrigins("http://localhost:5173,http://localhost:3000")
                .build());
    }
}
