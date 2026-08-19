package com.qingyu.care.controller;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.qingyu.care.dto.AbTestUpdateRequest;
import com.qingyu.care.dto.AiModelConfigUpdateRequest;
import com.qingyu.care.dto.AiSkillUpdateRequest;
import com.qingyu.care.dto.AiToolUpdateRequest;
import com.qingyu.care.dto.ApplicationUpdateRequest;
import com.qingyu.care.dto.ChatStrategyUpdateRequest;
import com.qingyu.care.dto.KnowledgeBaseUpdateRequest;
import com.qingyu.care.dto.TenantUpdateRequest;
import com.qingyu.care.dto.UserUpdateRequest;
import com.qingyu.care.entity.AbExperiment;
import com.qingyu.care.entity.AbVariant;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.entity.AiSkill;
import com.qingyu.care.entity.AiTool;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.ExperimentStatus;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.AbExperimentRepository;
import com.qingyu.care.repository.AbTestReportDao;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.repository.ChatStrategyRepository;
import com.qingyu.care.repository.CrawlSiteRepository;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.repository.QaPairRepository;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.repository.VectorStoreRepository;
import com.qingyu.care.service.AbTestService;
import com.qingyu.care.service.AuthService;
import com.qingyu.care.service.ChatStrategyService;
import com.qingyu.care.service.ChatToolService;
import com.qingyu.care.service.EmbeddingModelProvider;
import com.qingyu.care.service.FileService;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.service.impl.KnowledgeServiceImpl;
import com.qingyu.care.service.webhook.WebhookPayloadBuilder;
import com.qingyu.care.service.webhook.WebhookSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 扫描式守护「{@code *UpdateRequest} 的每个字段都真的传播到了实体」。
 *
 * <p><b>这一跳此前零守护</b>（技术债第 18 项）。{@code UpdateRequestFieldCoverageTest} 守的是
 * 「实体 → DTO」——实体加了字段，DTO 必须跟上或显式声明排除；而「DTO → {@code if (x != null)
 * existing.setX(...)}」这一跳没有任何东西看着。给实体和 DTO 都加了字段、却漏掉那行赋值，
 * 两道既有哨兵<b>全绿</b>，症状与技术债第 12 项的原始失效一字不差：字段在界面上改不动，
 * 无报错、无日志，只有用户来报「我改了保存没反应」。
 *
 * <p><b>做法</b>：对每条真实更新路径，反射把 DTO 的每个字段填成「与实体当前值不同」的哨兵值，
 * 执行那条路径，再断言实体上的同名字段<b>确实变了</b>。DTO 加一个字段而忘了加映射，本类当场红。
 *
 * <p>⚠️ <b>断言口径是「变了」而不是「等于 DTO 里那个值」</b>，这是有意的：
 * {@code UserUpdateRequest.password} 落到实体上是 {@code BCrypt.hashpw} 的结果，每次加盐都不同，
 * 比对具体值必然误报。代价是弱一点——「映射到了错的字段」这类错误抓不到——但本类要防的是
 * <b>漏映射</b>，「变了」对这个目标是充分的。
 *
 * <p>⚠️ <b>被测的是「更新路径」而非「控制器」</b>：映射代码分布在两层——
 * {@code AiTool}/{@code AiSkill}/{@code AiModelConfig}/{@code Application}/{@code Tenant}/
 * {@code User}/{@code ChatStrategy} 写在控制器里，而 {@code AbExperiment} 在
 * {@code AbTestService.update}、{@code KnowledgeBase} 在
 * {@code KnowledgeServiceImpl.updateKnowledgeBase}。若把本类写死成「调控制器」，后两条会因为
 * service 被 mock 掉而<b>静默零覆盖</b>——测试照样绿。故每条 case 自带一个执行器 lambda，
 * 由它决定该调哪一层。
 *
 * <p>⚠️ <b>{@code AiModelConfigUpdateRequest} 与 {@code AiToolUpdateRequest} 各有两条路径</b>
 * （租户侧 + {@code PlatformController} 的共享侧），两份映射代码是各自独立抄写的、会各自漂移，
 * 因此两条都必须登记。只测其中一条时，另一条漏字段不会被发现。
 *
 * <p><b>三轮变异验证</b>（每轮都确认过变异真的落地，避免「变异没落地」与「守护失效」在输出上
 * 无法区分）：
 * <ol>
 *   <li>删 {@code ChatStrategyController} 的 {@code setRrfK} 一行 → 红。（该字段另有
 *       {@code PartialUpdateSemanticsTest} 的窄测试同时转红，本轮不能单独证明本类的价值。）</li>
 *   <li>删 {@code PlatformController.updateSharedTool} 的 {@code setMaxResponseChars} 一行 →
 *       <b>全仓 713 条测试里只有本类转红</b>。这一轮才是本类存在的证据：平台共享那条路径的
 *       映射此前没有任何测试碰过。</li>
 *   <li>把登记表砍成一条 case → 「一个字段都没实际比对过」的空转哨兵转红。</li>
 * </ol>
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UpdateRequestPropagationTest {

    private static final Long ID = 1L;

    @Mock ChatToolService chatToolService;
    @Mock ModelRouterService modelRouterService;
    @Mock ApplicationRepository applicationRepository;
    @Mock WebhookSender webhookSender;
    @Mock WebhookPayloadBuilder webhookPayloadBuilder;
    @Mock TenantRepository tenantRepository;
    @Mock AuthService authService;
    @Mock UserRepository userRepository;
    @Mock ChatStrategyService chatStrategyService;
    @Mock ChatSessionRepository chatSessionRepository;
    @Mock HealthEndpoint healthEndpoint;

    @Mock AbExperimentRepository abExperimentRepository;
    @Mock ChatStrategyRepository chatStrategyRepository;
    @Mock AbTestReportDao abTestReportDao;

    @Mock KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock DocumentRepository documentRepository;
    @Mock VectorStore vectorStore;
    @Mock FileService fileService;
    @Mock VectorStoreRepository vectorStoreRepository;
    @Mock EmbeddingModelProvider embeddingModelProvider;
    @Mock QaPairRepository qaPairRepository;
    @Mock CrawlSiteRepository crawlSiteRepository;

    // 一律用 @InjectMocks 而非手工 new：本类登记了 11 条路径、涉及 9 个被测类，
    // 手工 new 会把每个构造器的参数个数与顺序都耦合进来，加一个依赖就得改一片。
    @InjectMocks AiToolController aiToolController;
    @InjectMocks AiSkillController aiSkillController;
    @InjectMocks AiModelController aiModelController;
    @InjectMocks ChatStrategyController chatStrategyController;
    @InjectMocks ApplicationController applicationController;
    @InjectMocks TenantController tenantController;
    @InjectMocks UserController userController;
    @InjectMocks PlatformController platformController;
    @InjectMocks AbTestService abTestService;
    @InjectMocks KnowledgeServiceImpl knowledgeService;

    @AfterEach
    void clear() {
        com.qingyu.care.config.TenantContext.clear();
    }

    /**
     * 一条真实的更新路径。
     *
     * @param dtoType  请求体类型，其全部字段都会被填上哨兵值
     * @param label    失败信息里用的名字，指向映射代码的真实位置
     * @param seed     造一个「已存在」的实体；字段值会被读作 before
     * @param invoke   执行这条更新路径（自带所需的 stub）
     * @param custom   通用哨兵生成器造不出来的字段，在这里显式给值
     */
    private record UpdateCase(Class<?> dtoType, String label, Supplier<Object> seed,
                              BiConsumer<Object, Object> invoke, Map<String, Object> custom) {
        UpdateCase(Class<?> dtoType, String label, Supplier<Object> seed, BiConsumer<Object, Object> invoke) {
            this(dtoType, label, seed, invoke, Map.of());
        }
    }

    private List<UpdateCase> cases() {
        List<UpdateCase> cases = new ArrayList<>();

        cases.add(new UpdateCase(AiToolUpdateRequest.class, "AiToolController.update",
                () -> AiTool.builder().id(ID).name("旧工具").build(),
                (entity, dto) -> {
                    when(chatToolService.getTool(ID)).thenReturn((AiTool) entity);
                    aiToolController.update(ID, (AiToolUpdateRequest) dto);
                }));

        cases.add(new UpdateCase(AiToolUpdateRequest.class, "PlatformController.updateSharedTool",
                () -> AiTool.builder().id(ID).name("旧共享工具").scope(OwnerScope.PLATFORM).build(),
                (entity, dto) -> {
                    when(chatToolService.getTool(ID)).thenReturn((AiTool) entity);
                    try (MockedStatic<StpUtil> ignored = stubRole(UserRole.PLATFORM_ADMIN.name())) {
                        platformController.updateSharedTool(ID, (AiToolUpdateRequest) dto);
                    }
                }));

        cases.add(new UpdateCase(AiSkillUpdateRequest.class, "AiSkillController.update",
                () -> AiSkill.builder().id(ID).name("旧技能").build(),
                (entity, dto) -> {
                    when(chatToolService.getSkill(ID)).thenReturn((AiSkill) entity);
                    aiSkillController.update(ID, (AiSkillUpdateRequest) dto);
                }));

        cases.add(new UpdateCase(AiModelConfigUpdateRequest.class, "AiModelController.updateModel",
                () -> AiModelConfig.builder().id(ID).name("旧模型").build(),
                (entity, dto) -> {
                    when(modelRouterService.getById(ID)).thenReturn((AiModelConfig) entity);
                    aiModelController.updateModel(ID, (AiModelConfigUpdateRequest) dto);
                }));

        cases.add(new UpdateCase(AiModelConfigUpdateRequest.class, "PlatformController.updateSharedModel",
                () -> AiModelConfig.builder().id(ID).name("旧共享模型").scope(OwnerScope.PLATFORM).build(),
                (entity, dto) -> {
                    when(modelRouterService.getById(ID)).thenReturn((AiModelConfig) entity);
                    try (MockedStatic<StpUtil> ignored = stubRole(UserRole.PLATFORM_ADMIN.name())) {
                        platformController.updateSharedModel(ID, (AiModelConfigUpdateRequest) dto);
                    }
                }));

        cases.add(new UpdateCase(ChatStrategyUpdateRequest.class, "ChatStrategyController.update",
                () -> ChatStrategy.builder().id(ID).name("旧策略").build(),
                (entity, dto) -> {
                    when(chatStrategyService.getById(ID)).thenReturn((ChatStrategy) entity);
                    chatStrategyController.update(ID, (ChatStrategyUpdateRequest) dto);
                }));

        cases.add(new UpdateCase(ApplicationUpdateRequest.class, "ApplicationController.update",
                // tenant 必须给：update 先走 checkAppAccess，非平台管理员时要拿 app.getTenant().getId()
                () -> Application.builder().id(ID).name("旧应用")
                        .tenant(Tenant.builder().id(9L).name("云舟科技").build()).build(),
                (entity, dto) -> {
                    when(applicationRepository.findById(ID)).thenReturn(Optional.of((Application) entity));
                    when(applicationRepository.save(any(Application.class))).thenAnswer(i -> i.getArgument(0));
                    when(authService.isPlatformAdmin()).thenReturn(true);
                    applicationController.update(ID, (ApplicationUpdateRequest) dto);
                }));

        cases.add(new UpdateCase(TenantUpdateRequest.class, "TenantController.update",
                () -> Tenant.builder().id(ID).name("旧租户").build(),
                (entity, dto) -> {
                    when(tenantRepository.findById(ID)).thenReturn(Optional.of((Tenant) entity));
                    when(tenantRepository.save(any(Tenant.class))).thenAnswer(i -> i.getArgument(0));
                    tenantController.update(ID, (TenantUpdateRequest) dto);
                }));

        cases.add(new UpdateCase(UserUpdateRequest.class, "UserController.updateUser",
                () -> {
                    User u = User.builder().id(ID).username("zhangsan").role(UserRole.TENANT_MEMBER).build();
                    u.setPassword("$2a$10$OLD_HASH_PLACEHOLDER");
                    u.setNickname("旧昵称");
                    u.setEnabled(true);
                    u.setTenant(Tenant.builder().id(2L).name("云舟科技").build());
                    return u;
                },
                (entity, dto) -> {
                    when(userRepository.findById(ID)).thenReturn(Optional.of((User) entity));
                    when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
                    // enabled 与 role 两个字段有管理员门禁，用平台管理员身份跑才能验到它们的映射；
                    // 门禁本身由 UserControllerTest 覆盖，本类只管「字段有没有传播」。
                    try (MockedStatic<StpUtil> ignored = stubRole(UserRole.PLATFORM_ADMIN.name())) {
                        userController.updateUser(ID, (UserUpdateRequest) dto);
                    }
                }));

        cases.add(new UpdateCase(AbTestUpdateRequest.class, "AbTestService.update",
                () -> AbExperiment.builder().id(ID).name("旧实验").status(ExperimentStatus.DRAFT).build(),
                (entity, dto) -> {
                    when(abExperimentRepository.findById(ID)).thenReturn(Optional.of((AbExperiment) entity));
                    when(abExperimentRepository.save(any(AbExperiment.class))).thenAnswer(i -> i.getArgument(0));
                    when(chatStrategyRepository.findById(any())).thenAnswer(
                            i -> Optional.of(ChatStrategy.builder().id(i.getArgument(0)).build()));
                    abTestService.update(ID, (AbTestUpdateRequest) dto);
                },
                // 变体列表通用生成器造不出来：至少两个、且每个都要能过 validateVariants
                Map.of("variants", List.of(
                        AbVariant.builder().name("哨兵A").strategyId(11L).weight(50).build(),
                        AbVariant.builder().name("哨兵B").strategyId(12L).weight(50).build()))));

        cases.add(new UpdateCase(KnowledgeBaseUpdateRequest.class, "KnowledgeServiceImpl.updateKnowledgeBase",
                () -> {
                    com.qingyu.care.config.TenantContext.setTenantId(1L);
                    KnowledgeBase kb = new KnowledgeBase();
                    kb.setId(ID);
                    kb.setName("旧知识库");
                    kb.setDescription("旧描述");
                    kb.setTenantId(1L);
                    return kb;
                },
                (entity, dto) -> {
                    when(knowledgeBaseRepository.findById(ID)).thenReturn(Optional.of((KnowledgeBase) entity));
                    when(knowledgeBaseRepository.save(any(KnowledgeBase.class))).thenAnswer(i -> i.getArgument(0));
                    knowledgeService.updateKnowledgeBase(ID, (KnowledgeBaseUpdateRequest) dto);
                }));

        return cases;
    }

    /**
     * 桩当前登录身份。{@code isLogin} 也要桩——{@code SecurityUtil.getRole} 先问
     * {@code StpUtil.isLogin()}，不桩它会走进 catch 返回 null，平台侧两条路径直接 403。
     */
    private static MockedStatic<StpUtil> stubRole(String role) {
        SaSession session = mock(SaSession.class);
        when(session.get("role")).thenReturn(role);
        MockedStatic<StpUtil> stp = mockStatic(StpUtil.class);
        stp.when(StpUtil::isLogin).thenReturn(true);
        stp.when(StpUtil::getSession).thenReturn(session);
        return stp;
    }

    @Test
    @DisplayName("每条更新路径：DTO 的每个字段都必须传播到实体上的同名字段")
    void DTO每个字段都必须传播到实体() throws Exception {
        List<UpdateCase> cases = cases();

        // 登记表空了、或 DTO 反射不出字段时，下面的循环会空转、断言退化成永真。
        assertThat(cases).as("一条更新路径都没登记，本类等于没有断言").isNotEmpty();

        List<String> violations = new ArrayList<>();
        int checkedFields = 0;

        for (UpdateCase testCase : cases) {
            Object entity = testCase.seed().get();
            Object dto = testCase.dtoType().getDeclaredConstructor().newInstance();

            List<Field> dtoFields = mutableFields(testCase.dtoType());
            if (dtoFields.isEmpty()) {
                violations.add(testCase.label() + " 的 " + testCase.dtoType().getSimpleName()
                        + " 反射不出任何字段——扫描逻辑坏了，这条 case 是空转的");
                continue;
            }

            // 先记下实体的 before，并按 before 造出「一定不同」的哨兵值填进 DTO
            Map<String, Object> before = new java.util.LinkedHashMap<>();
            Map<String, Object> missingOnEntity = new java.util.LinkedHashMap<>();
            for (Field dtoField : dtoFields) {
                Field entityField = findField(entity.getClass(), dtoField.getName());
                if (entityField == null) {
                    missingOnEntity.put(dtoField.getName(), null);
                    continue;
                }
                entityField.setAccessible(true);
                Object current = entityField.get(entity);
                before.put(dtoField.getName(), snapshot(current));

                Object sentinel = testCase.custom().containsKey(dtoField.getName())
                        ? testCase.custom().get(dtoField.getName())
                        : sentinelFor(dtoField, current);
                if (sentinel == null) {
                    violations.add(testCase.label() + "." + dtoField.getName()
                            + "（类型 " + dtoField.getType().getSimpleName()
                            + "）造不出哨兵值，请在该 case 的 custom 表里显式给一个");
                    continue;
                }
                dtoField.setAccessible(true);
                dtoField.set(dto, sentinel);
            }

            for (String name : missingOnEntity.keySet()) {
                violations.add(testCase.label() + " 的 DTO 字段 " + name
                        + " 在实体 " + entity.getClass().getSimpleName()
                        + " 上找不到同名字段——本类按同名匹配，改了名就得在这里对齐");
            }

            testCase.invoke().accept(entity, dto);

            for (Map.Entry<String, Object> entry : before.entrySet()) {
                Field entityField = findField(entity.getClass(), entry.getKey());
                entityField.setAccessible(true);
                Object after = snapshot(entityField.get(entity));
                checkedFields++;
                if (Objects.equals(entry.getValue(), after)) {
                    violations.add(testCase.label() + " 没有把 " + entry.getKey()
                            + " 传播到实体（更新前后都是 " + entry.getValue()
                            + "）——多半是漏了一行 if (x != null) existing.set"
                            + Character.toUpperCase(entry.getKey().charAt(0))
                            + entry.getKey().substring(1) + "(...)");
                }
            }
        }

        assertThat(checkedFields)
                .as("一个字段都没实际比对过——登记表或反射逻辑坏了，下面的断言是空转的")
                .isGreaterThan(40);

        assertThat(violations)
                .as("以下 DTO 字段没能传播到实体。这类缺陷完全静默：界面上改了保存没反应，"
                        + "无报错无日志，而「实体→DTO」那道哨兵照样全绿")
                .isEmpty();
    }

    /**
     * 集合字段必须拷一份再比，否则「变了」这个断言对它们<b>恒为假</b>。
     *
     * <p>{@code AbTestService.update} 走的是 {@code existing.getVariants().clear()} +
     * {@code addAll(...)}——原地改而非换引用，这是 JPA 托管集合的正确写法（直接 setter 换掉整个
     * 集合会撞上 orphanRemoval）。直接存引用的话 before 与 after 是同一个对象，内容一起变，
     * {@code Objects.equals} 永远成立，该字段的检查会静默退化成永真。
     */
    private static Object snapshot(Object value) {
        if (value instanceof Set<?> set) {
            return new LinkedHashSet<>(set);
        }
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        if (value instanceof Map<?, ?> map) {
            return new java.util.LinkedHashMap<>(map);
        }
        return value;
    }

    /** 取类自身声明的非 static、非合成字段。DTO 都是扁平的，不向上找父类。 */
    private static List<Field> mutableFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        for (Field field : clazz.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            fields.add(field);
        }
        return fields;
    }

    /** 按名字在实体及其父类里找字段（实体继承 BaseTenantEntity）。 */
    private static Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 造一个与 {@code current} 一定不同的值。字符串刻意造成非空白——{@code apiKey}/
     * {@code authHeader}/{@code password} 三处的判空是 {@code isBlank()}，空白值会被正确地
     * 忽略掉，那样本类会误报成「没传播」。
     */
    private static Object sentinelFor(Field field, Object current) {
        Class<?> type = field.getType();

        if (type == String.class) {
            return "哨兵-" + field.getName();
        }
        if (type == Boolean.class || type == boolean.class) {
            return !Boolean.TRUE.equals(current);
        }
        if (type == Integer.class || type == int.class) {
            return Integer.valueOf(4242).equals(current) ? 4243 : 4242;
        }
        if (type == Long.class || type == long.class) {
            return Long.valueOf(4242L).equals(current) ? 4243L : 4242L;
        }
        if (type == Double.class || type == double.class) {
            return Double.valueOf(0.4242).equals(current) ? 0.4243 : 0.4242;
        }
        if (type == LocalDateTime.class) {
            LocalDateTime sentinel = LocalDateTime.of(2030, 1, 2, 3, 4, 5);
            return sentinel.equals(current) ? sentinel.plusDays(1) : sentinel;
        }
        if (type.isEnum()) {
            for (Object constant : type.getEnumConstants()) {
                if (!constant.equals(current)) {
                    return constant;
                }
            }
            return null;
        }
        if (type == Set.class) {
            return new LinkedHashSet<>(Set.of(4242L));
        }
        return null;   // 造不出来的交给 case 的 custom 表
    }
}
