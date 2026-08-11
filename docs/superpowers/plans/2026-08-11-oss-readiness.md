# 开源就绪 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让轻语这个仓库能被陌生人 clone 下来看懂、跑起来、照文档用起来——一条命令产出可截图可试用的真实演示数据，README 与手册配实拍截图，部署文档带可运行的容器化产物，仓库里的生成物与错位文档清干净。

**Architecture:** 演示数据是一个被 `@Profile("demo")` 隔离的 `ApplicationRunner`，走真实业务服务（`uploadDocument` / `qaPairService.create` / `analyticsRollupService.backfill`）而非直接构造实体，因此产出的数据与真人操作完全同构、向量真实可检索。截图基于该数据用 chrome-devtools 实拍。文档与容器化产物是纯新增/重写，不触碰业务逻辑。

**Tech Stack:** Spring Boot 4.1.0 · Spring AI 2.0.0 · JDK 17 · PostgreSQL 17 + pgvector + zhparser · React 19 + Vite 7 · Docker Compose

## Global Constraints

- 全部使用简体中文：文档正文、代码注释、commit 信息。
- 新增文件若写作者，一律 `@author ruan`。
- 分支：`feature/oss-readiness`（已建，设计文档已提交）。不 `git push`。
- **`AI_BASE_URL` 必须自带 `/v1`**——正确值 `https://dashscope.aliyuncs.com/compatible-mode/v1`。全plan 中凡出现该变量处一律带 `/v1`，这是本次要修的核心错误之一，不能在新文档里复发。
- 演示租户 code 固定 `demo-cloudboat`；演示应用 appKey 固定 `demo-cloudboat-shop`、appSecret 固定 `demo-secret-cloudboat-shop-0001`（文档中须标注「演示专用，生产必须重新生成」）。
- 演示账号：`demo_admin` / `demo123456`（TENANT_ADMIN），`demo_agent` / `demo123456`（TENANT_MEMBER）。
- 截图统一视口 1440×900，存 `docs/images/`，命名 `NN-<英文短名>.png`。
- 不新增自动化测试（播种类是 profile 隔离旁路代码）；但 `mvn test` 必须保持全绿——现有的 `AsyncExecutorRoutingTest` 与 `AsyncDispatchOutsideTransactionTest` 会扫描新代码。
- `docs/superpowers/` 目录**不迁移**（设计 §6.2）。

---

## File Structure

**新增代码**

| 文件 | 职责 |
|------|------|
| `backend/src/main/java/com/qingyu/care/config/DemoDataSeeder.java` | 演示数据播种编排：幂等判定、租户上下文搬运、调各业务服务 |
| `backend/src/main/java/com/qingyu/care/config/DemoDataContent.java` | 演示文案常量（4 篇文档正文、8 条 QA、12 组会话对话）——与编排逻辑分开，避免播种类被长字符串淹没 |
| `backend/Dockerfile` | 后端多阶段构建镜像 |
| `frontend/Dockerfile` | 前端多阶段构建 + nginx 托管 |
| `frontend/nginx.conf` | SPA fallback + `/api` 反代 + SSE 不缓冲 |
| `docker/docker-compose.prod.yml` | 生产全栈编排 |

**新增文档**

| 文件 | 职责 |
|------|------|
| `LICENSE` | MIT |
| `.env.example` | 环境变量模板 |
| `docs/README.md` | 文档索引（区分使用文档与开发存档） |
| `docs/使用手册.md` | 面向租户管理员的端到端操作指南 |
| `docs/技术债清单.md` | 从 `SaaS平台规划.md` 拆出 |
| `docs/images/*.png` | 14 张截图 |

**修改**

| 文件 | 改动 |
|------|------|
| `README.md` | 重写 |
| `docs/部署文档.md` | 补容器化/HTTPS/备份演练/监控/故障手册 |
| `SaaS平台规划.md` | 技术债段落换成指针 |
| `CLAUDE.md` | 补 demo profile 用法与截图重拍说明 |

**删除**：`docs/architecture/`、`docs/名字.png`

---

## Task 1: 演示数据播种骨架——租户 / 应用 / 用户

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/config/DemoDataSeeder.java`

**Interfaces:**
- Consumes: `TenantRepository.findByCode(String) → Optional<Tenant>`、`ApplicationRepository`、`UserRepository`（均为标准 `JpaRepository`）
- Produces: `DemoDataSeeder` 私有方法 `seedTenant() → Tenant`、`seedApp(Tenant, String name, String appKey, String appSecret) → Application`、`seedUser(Tenant, String username, String nickname, UserRole) → User`；常量 `DEMO_TENANT_CODE`、`DEMO_APP_KEY`、`DEMO_APP_SECRET`

- [ ] **Step 1: 创建播种类骨架**

创建 `backend/src/main/java/com/qingyu/care/config/DemoDataSeeder.java`：

```java
package com.qingyu.care.config;

import cn.dev33.satoken.secure.BCrypt;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.enums.TenantStatus;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

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
```

- [ ] **Step 2: 编译验证**

Run: `cd backend && mvn -q compile`
Expected: BUILD SUCCESS，无编译错误。

- [ ] **Step 3: 确认现有测试未被新代码打破**

Run: `cd backend && mvn test`
Expected: 全绿。特别关注 `AsyncExecutorRoutingTest` 与 `AsyncDispatchOutsideTransactionTest`——本类没有 `@Async` 也没有事务内投递，应当无影响。

- [ ] **Step 4: 真机验证播种与幂等**

Run（前台跑，看日志后 Ctrl+C）：
```bash
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev,demo
```
Expected: 日志出现「开始播种演示数据」→「演示租户/应用/用户就绪 tenantId=… appId=… adminId=…」→「演示数据播种完成」。

再启动一次，Expected: 只出现「演示数据已存在（租户 code=demo-cloudboat），跳过播种」。

查库确认：
```bash
docker exec -i qingyu-postgres psql -U postgres -d qingyu_care -c \
  "SELECT id,name,code,plan FROM tenant WHERE code='demo-cloudboat';
   SELECT id,name,app_key FROM application WHERE app_key LIKE 'demo-%';
   SELECT id,username,role FROM \"user\" WHERE username LIKE 'demo_%';"
```
Expected: 1 个租户、2 个应用、2 个用户。

> 注：表名 `user` 是 PostgreSQL 保留字，须加双引号。若实际表名不同，以 `\dt` 输出为准。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/config/DemoDataSeeder.java
git commit -m "feat(demo): 演示数据播种骨架——租户/应用/用户

@Profile(\"demo\") 隔离 + ApplicationRunner 排在四个配置服务的 @PostConstruct 之后。
幂等键是租户 code，反复重启不重复播种。播种失败只记 error 不外抛——演示数据是
旁路不是主链路，不该让应用起不来。

appKey/appSecret 固定而非随机：使用手册里的 Widget 嵌入代码要能直接写死它，
读者照抄就能跑通。"
```

---

## Task 2: 知识库 + 4 篇文档 + 8 条 QA（真实摄取，含租户上下文搬运）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/config/DemoDataContent.java`
- Modify: `backend/src/main/java/com/qingyu/care/config/DemoDataSeeder.java`

**Interfaces:**
- Consumes: Task 1 的 `seed()`、`DEMO_TENANT_CODE`；`KnowledgeService.createKnowledgeBase(KnowledgeBaseRequest) → KnowledgeBase`、`KnowledgeService.uploadDocument(Long, MultipartFile) → Document`、`KnowledgeService.getDocumentStatus(Long) → DocumentStatusDTO`、`QaPairService.create(Long, String, String) → QaPair`
- Produces: `DemoDataContent.DOCUMENTS` (`List<DemoDoc>`，`record DemoDoc(String filename, String markdown)`)、`DemoDataContent.QA_PAIRS` (`List<String[]>`，每项 `{question, answer}`)；`DemoDataSeeder.seedKnowledge(Long appId) → Long`（返回知识库 id）

- [ ] **Step 1: 创建演示文案常量类**

创建 `backend/src/main/java/com/qingyu/care/config/DemoDataContent.java`。正文要足够真实——截图里文档的切分块数、字数都是它决定的，占位文本会露馅：

```java
package com.qingyu.care.config;

import java.util.List;

/**
 * 演示数据的文案常量：知识库文档正文与 QA 问答对。
 *
 * <p>与 {@link DemoDataSeeder} 分开，是为了不让编排逻辑被长字符串淹没——播种类要能一眼
 * 看清「先做什么再做什么」，正文属于内容不属于流程。
 *
 * @author ruan
 */
final class DemoDataContent {

    private DemoDataContent() {
    }

    /** 一篇演示文档：文件名 + Markdown 正文 */
    record DemoDoc(String filename, String markdown) {
    }

    static final List<DemoDoc> DOCUMENTS = List.of(
            new DemoDoc("退换货政策.md", """
                    # 云舟商城退换货政策

                    ## 七天无理由退货

                    自您签收商品之日起 7 个自然日内，商品保持完好、不影响二次销售的，
                    可申请无理由退货。退货运费由买家承担，若因商品质量问题退货则由云舟商城承担。

                    以下商品不支持七天无理由退货：
                    - 已拆封的个人护理类商品（如耳机入耳套、美妆试用装）
                    - 定制类商品（含刻字、定制尺寸）
                    - 生鲜、食品类商品
                    - 数字化商品（激活码、会员权益）

                    ## 质量问题换货

                    商品自签收之日起 15 日内出现性能故障，经云舟商城售后检测确认属于质量问题的，
                    可申请免费换货。换货商品与原商品同款同规格，若该规格缺货则协商退款。

                    ## 退款到账时间

                    | 支付方式 | 到账时间 |
                    |----------|----------|
                    | 支付宝 | 1-3 个工作日 |
                    | 微信支付 | 1-3 个工作日 |
                    | 银行卡 | 3-7 个工作日 |
                    | 云舟余额 | 实时到账 |

                    ## 申请流程

                    1. 登录云舟商城，进入「我的订单」
                    2. 找到目标订单点击「申请售后」
                    3. 选择退货或换货，填写原因并上传凭证照片
                    4. 等待客服审核（通常 2 小时内）
                    5. 审核通过后按页面提示寄回商品

                    ## 特殊说明

                    大家电类商品（冰箱、洗衣机、空调）因体积原因，退换货需预约上门取件，
                    取件时间为工作日 9:00-18:00，请保持电话畅通。
                    """),

            new DemoDoc("配送与物流说明.md", """
                    # 云舟商城配送与物流说明

                    ## 配送范围

                    云舟商城目前支持中国大陆 31 个省市自治区配送，暂不支持港澳台及海外地区。
                    部分偏远地区（新疆、西藏、内蒙古部分区县）配送时效会延长 1-3 天。

                    ## 发货时效

                    - **现货商品**：付款成功后 24 小时内发货
                    - **预售商品**：以商品详情页标注的发货时间为准
                    - **定制商品**：付款后 3-7 个工作日发货

                    每日 15:00 前完成付款的现货订单，当日安排发货；15:00 后的订单顺延至次日。

                    ## 运费规则

                    | 订单金额 | 运费 |
                    |----------|------|
                    | 满 99 元 | 包邮 |
                    | 不满 99 元 | 10 元 |
                    | 偏远地区不满 199 元 | 20 元 |

                    云舟 PLUS 会员全场包邮，不受订单金额限制。

                    ## 物流查询

                    订单发货后会通过短信推送运单号。您也可以登录云舟商城，
                    在「我的订单」中点击「查看物流」实时追踪。

                    ## 签收须知

                    请当面验货后再签收。若外包装有明显破损、挤压变形，
                    您有权拒收并联系客服，我们将安排重新发货。

                    已签收后发现商品破损的，请在 24 小时内联系客服并提供开箱视频。

                    ## 配送异常处理

                    - **超时未送达**：预计送达时间后 48 小时仍未收到，可申请物流查询，
                      确认丢件的将全额退款或补发。
                    - **地址填错**：发货前可在订单页修改地址；已发货的需联系快递方改派，
                      产生的费用由买家承担。
                    """),

            new DemoDoc("会员与积分规则.md", """
                    # 云舟会员与积分规则

                    ## 会员等级

                    云舟商城设置四个会员等级，按近 12 个月累计消费金额自动升降级：

                    | 等级 | 累计消费 | 折扣 | 生日礼 |
                    |------|----------|------|--------|
                    | 普通会员 | 0 元起 | 无 | 无 |
                    | 银卡会员 | 2000 元 | 98 折 | 50 元券 |
                    | 金卡会员 | 8000 元 | 95 折 | 100 元券 |
                    | 钻石会员 | 30000 元 | 92 折 | 300 元券 + 专属客服 |

                    等级每月 1 日凌晨自动核算更新。降级时保留原等级权益至当月月底。

                    ## 云舟 PLUS 会员

                    PLUS 会员为付费订阅制，99 元/年，权益包括：
                    - 全场包邮，不限订单金额
                    - 每月 4 张 20 元无门槛券
                    - 专属客服通道，排队优先
                    - 退换货免运费

                    PLUS 会员与等级会员权益可叠加使用。

                    ## 积分获取

                    - 消费返积分：每消费 1 元返 1 积分，PLUS 会员双倍
                    - 每日签到：连续签到 7 天累计可得 70 积分
                    - 评价晒单：图文评价 20 积分，视频评价 50 积分
                    - 邀请好友：好友首单完成后邀请人得 500 积分

                    ## 积分使用

                    100 积分 = 1 元，可在结算时直接抵扣，单笔订单最多抵扣订单金额的 50%。
                    积分也可在积分商城兑换实物礼品与优惠券。

                    ## 积分有效期

                    积分自获得之日起 24 个月内有效，过期自动清零。
                    系统会在过期前 30 天通过站内信提醒。

                    ## 常见问题

                    **退货后积分怎么算？** 退货成功后，该笔订单产生的积分会相应扣回；
                    若积分已使用，将从账户余额中扣除等值金额。

                    **积分可以转赠吗？** 目前不支持积分转赠给其他账号。
                    """),

            new DemoDoc("支付与发票.md", """
                    # 云舟商城支付与发票

                    ## 支持的支付方式

                    - 支付宝（含花呗分期）
                    - 微信支付
                    - 银联云闪付
                    - 主流银行储蓄卡与信用卡
                    - 云舟余额与云舟礼品卡

                    ## 分期免息

                    支持花呗与主流银行信用卡分期。标注「分期免息」的商品，
                    3 期免息由云舟商城承担手续费；6 期及以上手续费由买家承担。

                    ## 订单支付时限

                    普通订单下单后 30 分钟内未支付将自动取消并释放库存。
                    预售订单定金支付后，尾款需在商品详情页标注的时间窗口内支付，逾期定金不退。

                    ## 发票类型

                    | 类型 | 开具时间 | 说明 |
                    |------|----------|------|
                    | 电子普通发票 | 发货后 24 小时内 | 默认类型，发送至下单邮箱 |
                    | 增值税专用发票 | 收货后 3 个工作日 | 需提供完整开票资料 |
                    | 纸质普通发票 | 收货后 7 个工作日 | 随包裹寄出或单独邮寄 |

                    ## 开票资料

                    申请增值税专用发票需提供：单位名称、纳税人识别号、注册地址、
                    注册电话、开户银行、银行账号。资料填写错误导致无法开具的，
                    需先申请作废后重新开具。

                    ## 发票重开与补开

                    - **重开**：电子发票开具后 90 天内可申请红冲重开
                    - **补开**：未开票订单在完成后 12 个月内均可补开

                    ## 退款与发票

                    发生退货退款时，对应发票需作废或红冲。
                    若为部分退款，将按实际成交金额重新开具。
                    """)
    );

    /** 8 条演示 QA 问答对：{问题, 答案} */
    static final List<String[]> QA_PAIRS = List.of(
            new String[]{"你们的客服工作时间是几点到几点？",
                    "云舟商城在线客服的服务时间是每天 9:00-22:00。非服务时段您可以留言，我们会在次日 9:00 后优先回复。PLUS 会员享有 7×24 小时专属客服通道。"},
            new String[]{"忘记登录密码了怎么办？",
                    "请在登录页点击「忘记密码」，输入注册手机号获取验证码后即可重置。若手机号已停用，请携带身份证正面照与订单号联系人工客服申诉，审核周期为 1-3 个工作日。"},
            new String[]{"下单后可以修改收货地址吗？",
                    "订单发货前可以在「我的订单」详情页自行修改收货地址。已发货的订单需要联系快递公司申请改派，改派产生的额外费用由买家承担。"},
            new String[]{"云舟 PLUS 会员可以退订吗？",
                    "PLUS 会员自购买之日起 7 天内，若未使用过任何 PLUS 权益（包邮、优惠券、免运费退换），可申请全额退款。已使用过权益的按剩余天数折算退款。"},
            new String[]{"商品降价了可以申请差价吗？",
                    "云舟商城提供 15 天价格保护：订单签收后 15 天内，若同款商品在云舟自营降价，可申请补差价。促销活动价、优惠券后价格、秒杀价不参与价保。"},
            new String[]{"怎么联系人工客服？",
                    "在对话窗口输入「人工」或「转人工」即可转接人工客服。工作时段内平均等待时间约 2 分钟。您也可以拨打客服热线 400-000-0000。"},
            new String[]{"支持货到付款吗？",
                    "云舟商城目前不支持货到付款。所有订单需在线完成支付后才会安排发货。您可以选择支付宝花呗或信用卡分期来缓解资金压力。"},
            new String[]{"发票抬头填错了能改吗？",
                    "电子普通发票开具后 90 天内可以申请红冲重开，在「我的订单」-「发票管理」中提交重开申请即可。增值税专用发票需先寄回原件才能作废重开。"}
    );
}
```

- [ ] **Step 2: 在播种类里加入租户上下文搬运与知识库播种**

修改 `DemoDataSeeder.java`。**这一步是整个 plan 里最容易出事的地方**——`ApplicationRunner` 跑在启动线程，没有 `TenantContext`，不手工搬运则知识库与文档的 `tenant_id` 静默落 NULL，后果是对任何租户都检索不到（向量 metadata 写成 `tenantId: ""`，永远匹配不上真实租户 id），且全程无异常无日志。

先补 import 与字段：

```java
import com.qingyu.care.dto.DocumentStatusDTO;
import com.qingyu.care.dto.KnowledgeBaseRequest;
import com.qingyu.care.entity.Document;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.enums.DocumentStatus;
import com.qingyu.care.enums.DocumentVisibility;
import com.qingyu.care.service.KnowledgeService;
import com.qingyu.care.service.QaPairService;
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
```

字段新增：

```java
    private final KnowledgeService knowledgeService;
    private final QaPairService qaPairService;
```

把 `seed()` 改成：

```java
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
        // 不搬运的后果不是「对所有租户可见」，而是反过来「对任何租户都检索不到」：
        // processDocument 在 tenantId 为 null 时把向量 metadata 写成 "tenantId": ""，
        // 而 RetrievalScope 对非 null 租户拼的是 tenantId == '<真实id>' 等值条件，
        // 空串永远匹配不上。embedding 的钱照花、0 命中、无异常无日志。
        try {
            TenantContext.setTenantId(tenant.getId());
            TenantContext.setAppId(shopApp.getId());
            TenantContext.setUserId(admin.getId());
            TenantContext.setAccessLevel(DocumentVisibility.INTERNAL);
            Long kbId = seedKnowledge();
            log.info("演示知识库就绪 knowledgeBaseId={}", kbId);
        } finally {
            // 启动线程会被后续请求复用，不清理即泄漏给下一个使用者
            TenantContext.clear();
        }
    }
```

新增知识库播种方法：

```java
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

        for (String[] qa : DemoDataContent.QA_PAIRS) {
            qaPairService.create(kb.getId(), qa[0], qa[1]);
        }
        log.info("演示 QA 问答对已嵌入 count={}", DemoDataContent.QA_PAIRS.size());

        return kb.getId();
    }

    /**
     * 等文档摄取落地。
     *
     * <p>{@code uploadDocument()} 自带 {@code @Transactional}，内部经 {@code AfterCommit}
     * 把 {@code processDocumentAsync} 推迟到事务提交后投递，因此本方法返回时文档仍是 PENDING。
     * 不等的后果是截图时文档卡在「处理中」，且后续会话数据里的命中记录与实际向量对不上。
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
     * 极简 MultipartFile：把内存字节喂给 {@code uploadDocument()}，走与真人上传完全相同的路径。
     * 不用 spring-test 的 MockMultipartFile——那是 test scope，生产代码引不到。
     */
    private record InMemoryMultipartFile(String filename, byte[] content) implements MultipartFile {
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return filename; }
        @Override public String getContentType() { return "text/markdown"; }
        @Override public boolean isEmpty() { return content.length == 0; }
        @Override public long getSize() { return content.length; }
        @Override public byte[] getBytes() { return content; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(content); }
        @Override public void transferTo(File dest) throws IOException { Files.write(dest.toPath(), content); }
    }
```

还需补 `TenantContext` 的 import（同包 `com.qingyu.care.config`，无需 import）。

- [ ] **Step 3: 编译验证**

Run: `cd backend && mvn -q compile`
Expected: BUILD SUCCESS。

若报 `MultipartFile` 抽象方法未实现，按报错补齐（不同 Spring 版本的默认方法集合略有差异）。

- [ ] **Step 4: 清库后真机验证摄取**

先清掉 Task 1 播下的数据以便重播：
```bash
docker exec -i qingyu-postgres psql -U postgres -d qingyu_care -c \
  "DELETE FROM \"user\" WHERE username LIKE 'demo_%';
   DELETE FROM application WHERE app_key LIKE 'demo-%';
   DELETE FROM tenant WHERE code='demo-cloudboat';"
```

Run: `cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev,demo`
Expected 日志序列：4 条「演示文档已提交摄取」→「演示文档摄取完成 成功=4/4」→「演示 QA 问答对已嵌入 count=8」→「演示知识库就绪」。

- [ ] **Step 5: 验收 tenant_id 非 NULL（本 Task 的核心验收）**

```bash
docker exec -i qingyu-postgres psql -U postgres -d qingyu_care -c \
  "SELECT id,name,status,chunk_count,tenant_id,app_id FROM document
    WHERE knowledge_base_id=(SELECT id FROM knowledge_base WHERE name='云舟商城帮助中心');"
```
Expected: 4 行，`status` 全为 `COMPLETED`，`chunk_count > 0`，**`tenant_id` 与 `app_id` 均非 NULL**。

任一行 `tenant_id` 为 NULL 即说明 Step 2 的上下文搬运没生效——这是本设计点名的静默失败，必须修好再继续。

- [ ] **Step 6: 验收向量真实可检索**

```bash
docker exec -i qingyu-postgres psql -U postgres -d qingyu_care -c \
  "SELECT count(*) AS 向量行数 FROM vector_store;
   SELECT metadata->>'tenantId' AS 租户, count(*) FROM vector_store GROUP BY 1;"
```
Expected: 向量行数 > 0；`租户` 列是**真实租户 id 字符串**，不是空串也不是 null。

- [ ] **Step 7: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/config/DemoDataContent.java \
        backend/src/main/java/com/qingyu/care/config/DemoDataSeeder.java
git commit -m "feat(demo): 知识库 + 4 篇文档 + 8 条 QA，走真实摄取管线

文档经 uploadDocument() 真实上传（S3 + Tika + 切分 + 真实 embedding），
与真人操作产出的数据完全同构，截图里不会露馅。

两个坑已处理：
· ApplicationRunner 没有 TenantContext，不手工搬运则 tenant_id 静默落 NULL——
  后果是对任何租户都检索不到（metadata 写成 tenantId:\"\"），钱照花、0 命中、无日志。
  finally 里 clear()，启动线程会被后续请求复用。
· uploadDocument 经 AfterCommit 投递是真异步，返回时文档仍是 PENDING，
  故播种后轮询等 COMPLETED，否则截图时文档卡在处理中。"
```

---

## Task 3: 会话 / 消息 / 用量 / 分析日表（含时间回填）

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/config/DemoDataSeeder.java`
- Modify: `backend/src/main/java/com/qingyu/care/config/DemoDataContent.java`

**Interfaces:**
- Consumes: Task 2 的 `seedKnowledge() → Long`；`ChatSessionRepository`、`ChatMessageRepository`、`SysApiUsageRepository`、`AnalyticsRollupService.backfill(LocalDate, LocalDate) → int`、`JdbcTemplate`
- Produces: `DemoDataContent.CONVERSATIONS` (`List<DemoConversation>`，`record DemoConversation(String title, SessionTag tag, List<String[]> turns)`，`turns` 每项 `{用户问, AI答}`)；`DemoDataSeeder.seedSessions(...)`、`seedUsage(...)`

- [ ] **Step 1: 在文案类里加入 12 组会话**

在 `DemoDataContent.java` 补 import 与常量：

```java
import com.qingyu.care.enums.SessionTag;
```

```java
    /** 一组演示会话：标题（用于摘要）+ 分类标签 + 若干轮 {用户问, AI答} */
    record DemoConversation(String title, SessionTag tag, List<String[]> turns) {
    }

    static final List<DemoConversation> CONVERSATIONS = List.of(
            new DemoConversation("咨询七天无理由退货范围", SessionTag.AFTER_SALES, List.of(
                    new String[]{"我买的耳机拆封了还能退吗？",
                            "很抱歉，已拆封的个人护理类商品（包括耳机入耳套）不支持七天无理由退货。不过如果商品存在质量问题，签收后 15 日内可以申请免费换货，经售后检测确认后我们会为您处理。"},
                    new String[]{"那怎么算质量问题呢？",
                            "性能故障属于质量问题，比如单边无声、无法充电、连接不上设备等。您可以在「我的订单」中点击「申请售后」，上传故障视频，客服通常 2 小时内完成审核。"})),
            new DemoConversation("查询包邮门槛", SessionTag.PRE_SALES, List.of(
                    new String[]{"多少钱包邮？",
                            "订单满 99 元即可包邮；不满 99 元收取 10 元运费。偏远地区（新疆、西藏、内蒙古部分区县）需满 199 元包邮，否则收取 20 元。云舟 PLUS 会员全场包邮，不受金额限制。"})),
            new DemoConversation("积分过期时间", SessionTag.OTHER, List.of(
                    new String[]{"我的积分什么时候过期？",
                            "积分自获得之日起 24 个月内有效，过期会自动清零。系统会在过期前 30 天通过站内信提醒您。100 积分等于 1 元，可在结算时抵扣，单笔订单最多抵扣订单金额的 50%。"})),
            new DemoConversation("发票抬头开错", SessionTag.AFTER_SALES, List.of(
                    new String[]{"发票抬头写错了怎么办？",
                            "电子普通发票开具后 90 天内可以申请红冲重开，在「我的订单」-「发票管理」中提交重开申请即可。如果是增值税专用发票，需要先把原件寄回才能作废重开。"})),
            new DemoConversation("PLUS 会员权益咨询", SessionTag.PRE_SALES, List.of(
                    new String[]{"PLUS 会员值得买吗？有什么权益？",
                            "PLUS 会员 99 元/年，权益包括：全场包邮不限金额、每月 4 张 20 元无门槛券、专属客服排队优先、退换货免运费。每月券面值就有 80 元，如果您每月下单 2 次以上通常是划算的。"},
                    new String[]{"能退吗？",
                            "购买 7 天内且未使用过任何 PLUS 权益（包邮、优惠券、免运费退换）可以全额退款；已使用过权益的按剩余天数折算退款。"})),
            new DemoConversation("大家电退货上门取件", SessionTag.AFTER_SALES, List.of(
                    new String[]{"冰箱要退货，这么大怎么寄回去？",
                            "大家电类商品（冰箱、洗衣机、空调）退换货需要预约上门取件，不需要您自行寄回。取件时间为工作日 9:00-18:00，请在申请售后时选择方便的时段并保持电话畅通。"})),
            new DemoConversation("物流超时未送达", SessionTag.COMPLAINT, List.of(
                    new String[]{"下单一个星期了还没到，怎么回事？",
                            "非常抱歉给您带来不便。您可以在「我的订单」中点击「查看物流」实时追踪。如果超过预计送达时间 48 小时仍未收到，可以申请物流查询，确认丢件的我们会全额退款或补发。"},
                    new String[]{"那我要退款",
                            "好的，我这边帮您转接人工客服核实运单状态并办理退款，请稍等。"})),
            new DemoConversation("修改收货地址", SessionTag.PRE_SALES, List.of(
                    new String[]{"地址填错了能改吗？",
                            "订单发货前可以在「我的订单」详情页自行修改收货地址。如果已经发货了，需要联系快递公司申请改派，改派产生的额外费用需要由买家承担。"})),
            new DemoConversation("分期免息规则", SessionTag.PRE_SALES, List.of(
                    new String[]{"这个商品支持分期吗？利息高不高？",
                            "云舟商城支持花呗与主流银行信用卡分期。标注「分期免息」的商品，3 期免息由云舟承担手续费；6 期及以上的手续费需要由买家承担。具体以商品详情页标注为准。"})),
            new DemoConversation("价格保护申请", SessionTag.AFTER_SALES, List.of(
                    new String[]{"我刚买完就降价了，能退差价吗？",
                            "可以的。云舟商城提供 15 天价格保护：订单签收后 15 天内，同款商品在云舟自营降价可以申请补差价。需要注意促销活动价、优惠券后价格、秒杀价不参与价保。"})),
            new DemoConversation("账号登录异常", SessionTag.TECH_SUPPORT, List.of(
                    new String[]{"我忘记密码了，手机号也换了",
                            "请在登录页点击「忘记密码」用注册手机号重置。如果原手机号已停用，需要携带身份证正面照与任一历史订单号联系人工客服申诉，审核周期为 1-3 个工作日。"})),
            new DemoConversation("货到付款咨询", SessionTag.PRE_SALES, List.of(
                    new String[]{"支持货到付款吗？",
                            "很抱歉，云舟商城目前不支持货到付款，所有订单需要在线完成支付后才会安排发货。您可以选择支付宝花呗或信用卡分期来缓解资金压力，标注免息的商品 3 期是免手续费的。"}))
    );
```

- [ ] **Step 2: 在播种类里加入会话/用量/rollup**

补 import：

```java
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.entity.SysApiUsage;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.repository.SysApiUsageRepository;
import com.qingyu.care.service.AnalyticsRollupService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Random;
import java.util.UUID;
```

补字段：

```java
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final SysApiUsageRepository sysApiUsageRepository;
    private final AnalyticsRollupService analyticsRollupService;
    private final JdbcTemplate jdbcTemplate;
```

在 `seed()` 的 try 块内，`seedKnowledge()` 之后追加：

```java
            seedSessions(admin.getId());
            seedUsage(tenant.getId(), shopApp.getId());
            int days = analyticsRollupService.backfill(LocalDate.now().minusDays(29), LocalDate.now());
            log.info("演示分析日表回填完成 天数={}", days);
```

新增方法：

```java
    /**
     * 播种 12 组历史会话，按最近 30 天铺开。
     *
     * <p><b>为什么要用原生 SQL 回填时间</b>：ChatSession 与 ChatMessage 的 createdAt 带
     * {@code @CreationTimestamp}，Hibernate 在 INSERT 时会覆盖任何手工赋值。不回填的话
     * 12 条会话全部落在今天，而分析 rollup 是按 {@code cm.created_at} 分日聚合的
     * （见 AnalyticsRollupDao.insertQuality），30 天曲线会退化成一根柱子。
     */
    private void seedSessions(Long agentUserId) {
        Random random = new Random(20260811L);   // 固定种子：每次播种的分数与用量形态一致，截图可复现
        int index = 0;
        for (DemoDataContent.DemoConversation conv : DemoDataContent.CONVERSATIONS) {
            // 均匀铺在最近 30 天内，每条会话落在当天的营业时段
            int daysAgo = 29 - index * 29 / Math.max(1, DemoDataContent.CONVERSATIONS.size() - 1);
            LocalDateTime start = LocalDate.now().minusDays(daysAgo)
                    .atTime(9 + random.nextInt(12), random.nextInt(60));

            boolean handover = conv.tag() == SessionTag.COMPLAINT;   // 投诉类演示转人工
            ChatSession session = chatSessionRepository.save(ChatSession.builder()
                    .sessionId(UUID.randomUUID().toString())
                    .userId("demo-visitor-" + (index + 1))
                    .status(handover ? SessionStatus.AGENT_PROCESSING : SessionStatus.ENDED)
                    .tags(new HashSet<>(List.of(conv.tag())))
                    .summary(conv.title())
                    .agentId(handover ? agentUserId : null)
                    .handoverTime(handover ? start.plusMinutes(3) : null)
                    .endedAt(handover ? null : start.plusMinutes(5))
                    .build());
            backdateSession(session.getId(), start);

            int turn = 0;
            for (String[] qa : conv.turns()) {
                LocalDateTime askedAt = start.plusMinutes(turn * 2L);
                ChatMessage userMsg = chatMessageRepository.save(ChatMessage.builder()
                        .session(session).role(MessageRole.USER).content(qa[0]).build());
                backdateMessage(userMsg.getId(), askedAt);

                double confidence = 0.62 + random.nextDouble() * 0.33;   // 0.62~0.95
                ChatMessage aiMsg = chatMessageRepository.save(ChatMessage.builder()
                        .session(session).role(MessageRole.ASSISTANT).content(qa[1])
                        .confidenceScore(confidence)
                        .hitDocumentIds(String.valueOf(1 + random.nextInt(4)))
                        .tokenUsage(180 + random.nextInt(420))
                        .fallback(false)
                        .feedback(random.nextInt(4) == 0 ? 1 : null)     // 约 1/4 点赞
                        .build());
                backdateMessage(aiMsg.getId(), askedAt.plusSeconds(6));
                turn++;
            }
            index++;
        }
        log.info("演示会话已播种 count={}", DemoDataContent.CONVERSATIONS.size());
    }

    private void backdateSession(Long id, LocalDateTime ts) {
        jdbcTemplate.update("UPDATE chat_session SET created_at = ?, updated_at = ? WHERE id = ?", ts, ts, id);
    }

    private void backdateMessage(Long id, LocalDateTime ts) {
        jdbcTemplate.update("UPDATE chat_message SET created_at = ? WHERE id = ?", ts, id);
    }

    /**
     * 播种最近 30 天的开放 API 用量，让用量看板与计费额度页非空。
     * SysApiUsage 的 usageDate 是显式 LocalDate 字段，不受 @CreationTimestamp 影响，可直接设。
     */
    private void seedUsage(Long tenantId, Long appId) {
        Random random = new Random(20260812L);
        for (int i = 29; i >= 0; i--) {
            LocalDate day = LocalDate.now().minusDays(i);
            // 工作日高、周末低，让曲线有形状而不是一条直线
            boolean weekend = day.getDayOfWeek().getValue() >= 6;
            long calls = (weekend ? 40 : 120) + random.nextInt(weekend ? 30 : 90);
            sysApiUsageRepository.save(SysApiUsage.builder()
                    .tenantId(tenantId).appId(appId).usageDate(day)
                    .callCount(calls)
                    .messageCount(calls * 2)
                    .tokenCount(calls * (350L + random.nextInt(250)))
                    .build());
        }
        log.info("演示用量已播种 天数=30");
    }
```

- [ ] **Step 3: 编译验证**

Run: `cd backend && mvn -q compile`
Expected: BUILD SUCCESS。

- [ ] **Step 4: 清库重播并验证**

```bash
docker exec -i qingyu-postgres psql -U postgres -d qingyu_care -c \
  "DELETE FROM chat_message WHERE session_id IN (SELECT id FROM chat_session WHERE user_id LIKE 'demo-visitor-%');
   DELETE FROM chat_session WHERE user_id LIKE 'demo-visitor-%';
   DELETE FROM sys_api_usage WHERE tenant_id=(SELECT id FROM tenant WHERE code='demo-cloudboat');
   DELETE FROM qa_pair WHERE knowledge_base_id IN (SELECT id FROM knowledge_base WHERE name='云舟商城帮助中心');
   DELETE FROM vector_store WHERE metadata->>'tenantId'=(SELECT id::text FROM tenant WHERE code='demo-cloudboat');
   DELETE FROM document WHERE knowledge_base_id IN (SELECT id FROM knowledge_base WHERE name='云舟商城帮助中心');
   DELETE FROM knowledge_base WHERE name='云舟商城帮助中心';
   DELETE FROM \"user\" WHERE username LIKE 'demo_%';
   DELETE FROM application WHERE app_key LIKE 'demo-%';
   DELETE FROM tenant WHERE code='demo-cloudboat';"
```

Run: `cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev,demo`
Expected: 依次出现「演示会话已播种 count=12」「演示用量已播种 天数=30」「分析 rollup 完成 date=… quality=… keywords=… docHits=…」×30「演示分析日表回填完成 天数=30」。

- [ ] **Step 5: 验收时间真的铺开了（本 Task 的核心验收）**

```bash
docker exec -i qingyu-postgres psql -U postgres -d qingyu_care -c \
  "SELECT count(DISTINCT created_at::date) AS 会话覆盖天数 FROM chat_session WHERE user_id LIKE 'demo-visitor-%';
   SELECT count(*) AS 质量日表行数 FROM stat_quality_daily;
   SELECT count(*) AS 用量行数 FROM sys_api_usage;"
```
Expected: 会话覆盖天数 > 1（若等于 1 说明 `@CreationTimestamp` 回填没生效）；质量日表行数 > 0；用量行数 = 30。

- [ ] **Step 6: 全量单测回归**

Run: `cd backend && mvn test`
Expected: 全绿。

- [ ] **Step 7: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/config/
git commit -m "feat(demo): 会话/用量/分析日表，含 @CreationTimestamp 时间回填

12 组会话铺在最近 30 天、30 天用量曲线区分工作日与周末、分析日表经
analyticsRollupService.backfill 真实聚合而非手写假数据行。

⚠️ ChatSession/ChatMessage 的 createdAt 带 @CreationTimestamp，Hibernate 在
INSERT 时覆盖任何手工赋值，必须落库后用原生 UPDATE 回填。不回填则 12 条会话
全落在今天，而 rollup 按 cm.created_at 分日聚合，30 天曲线会退化成一根柱子。

Random 用固定种子：每次播种的分数与用量形态一致，截图可复现。"
```

---

## Task 4: 实拍 14 张截图

**Files:**
- Create: `docs/images/01-login.png` … `docs/images/14-widget-chat.png`

**Interfaces:**
- Consumes: Task 1-3 播下的演示数据；演示账号 `demo_admin` / `demo123456`
- Produces: `docs/images/` 下 14 个 PNG，供 Task 7、Task 8 引用

- [ ] **Step 1: 后台启动完整环境**

```bash
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev,demo
```
（后台运行）。前端确认在 `:5173`；不在则 `cd frontend && pnpm dev`。

等待后端就绪：`curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/actuator/health` 返回 `200`。

- [ ] **Step 2: 设定视口并登录**

用 chrome-devtools 打开 `http://localhost:5173`，`resize_page` 到 1440×900。
用 `demo_admin` / `demo123456` 登录。

⚠️ 登录后需在顶部应用选择器里选中「云舟商城客服」——应用级配置页外包了 `<RequireApp>`，未选应用时显示空状态，截出来是空白页。

- [ ] **Step 3: 逐页截图**

按下表导航并截图，每张存到 `docs/images/`：

| 文件名 | 路由 | 截图前的准备 |
|--------|------|--------------|
| `01-login.png` | `/login` | 退出登录后截；不要填密码 |
| `02-dashboard.png` | `/dashboard` | — |
| `03-knowledge-list.png` | `/knowledge` | 确认 4 篇文档都是「已完成」 |
| `04-document-detail.png` | `/knowledge` | 点开任一文档的详情/切分块 |
| `05-crawl-site.png` | `/knowledge` | 切到站点爬取标签页 |
| `06-strategy.png` | `/strategy` | — |
| `07-prompt.png` | `/prompt` | — |
| `08-model-config.png` | `/model` | — |
| `09-sessions.png` | `/session` | 确认列表有 12 条 |
| `10-agent-workbench.png` | `/agent` | — |
| `11-usage.png` | `/usage` | 确认 30 天曲线非空 |
| `12-analytics.png` | `/analytics` | 确认三个标签页有数据，截质量评估页 |
| `13-integration.png` | `/integration` | Widget 嵌入代码可见 |

> 路由以 `frontend/src/App.tsx` 实际定义为准，上表若与实际不符按实际走。

- [ ] **Step 4: 截 Widget 实机对话**

打开 `widget/index.html`（`cd widget && pnpm dev`）或用后端托管的 `http://localhost:8080/api/widget.js?appKey=demo-cloudboat-shop`。
提一个能命中知识库的问题，如「多少钱包邮？」，等回复完成后截 `14-widget-chat.png`。

若能触发卡片渲染（问「有哪几种支付方式」这类适合列表卡片的问题）则优先截带卡片的那张。

- [ ] **Step 5: 脱敏检查**

逐张打开 14 张 PNG 目视检查：不得出现真实 API Key、真实邮箱、本机路径、浏览器书签栏私人信息。
演示 appSecret 出现是允许的（已标注为演示专用）。

- [ ] **Step 6: 提交**

```bash
git add docs/images/
git commit -m "docs: 14 张管理后台与 Widget 实拍截图

基于 demo profile 演示数据实拍，1440x900 统一视口，供 README 与使用手册引用。
重拍方式记在 CLAUDE.md：起 dev,demo profile 后按 docs/images/ 的编号顺序走一遍。"
```

---

## Task 5: LICENSE 与 .env.example

**Files:**
- Create: `LICENSE`
- Create: `.env.example`

**Interfaces:**
- Produces: `.env.example` 定义的变量名，Task 6 的 `docker-compose.prod.yml` 直接消费

- [ ] **Step 1: 创建 LICENSE**

创建 `LICENSE`（标准 MIT 全文，版权行如下）：

```
MIT License

Copyright (c) 2026 ruan

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

- [ ] **Step 2: 创建 .env.example**

创建 `.env.example`：

```bash
# ============================================================
# 轻语（QingYu Care）环境变量模板
# 拷贝为 .env 后填入真实值：cp .env.example .env
# .env 已在 .gitignore 中，不会被提交
# ============================================================

# ---------- AI 模型（必填） ----------
# 通义千问 DashScope API Key，控制台：https://dashscope.console.aliyun.com/
DASHSCOPE_API_KEY=sk-your-dashscope-api-key

# ⚠️ base-url 必须自带 /v1
# Spring AI 2.0 起改用官方 openai-java SDK，只往 baseUrl 后拼 chat/completions 与
# embeddings，不再补 /v1。缺 /v1 会让对话、文档摄取、知识检索全线 404。
AI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
AI_CHAT_MODEL=qwen-plus
# 换 embedding 模型必须同步改向量维度，且需对存量向量跑一次全量重嵌
AI_EMBEDDING_MODEL=text-embedding-v3

# ---------- 数据库 ----------
POSTGRES_DB=qingyu_care
POSTGRES_USER=postgres
POSTGRES_PASSWORD=change-me-in-production
POSTGRES_PORT=5432

# ---------- Redis ----------
REDIS_PORT=6379

# ---------- 对象存储（RustFS，S3 兼容） ----------
RUSTFS_ACCESS_KEY=rustfsadmin
RUSTFS_SECRET_KEY=change-me-in-production
RUSTFS_BUCKET=qingyu-care
RUSTFS_PORT=9000
RUSTFS_CONSOLE_PORT=9001

# ---------- 应用 ----------
BACKEND_PORT=8080
FRONTEND_PORT=80
# Sa-Token 会话签名密钥，生产必须改成随机长字符串
SA_TOKEN_JWT_SECRET=change-me-to-a-long-random-string
```

- [ ] **Step 3: 核对 .env.example 的变量名与 application.yml 一致**

Run: `grep -nE "DASHSCOPE_API_KEY|AI_BASE_URL|AI_CHAT_MODEL|AI_EMBEDDING_MODEL" backend/src/main/resources/application.yml`
Expected: 四个变量名都能在 `application.yml` 里找到对应的 `${VAR:default}` 占位。名字对不上的以 `application.yml` 为准修改 `.env.example`。

其余变量（数据库/Redis/RustFS/端口）是给 Task 6 的 compose 用的，此刻不必与 `application.yml` 对应。

- [ ] **Step 4: 提交**

```bash
git add LICENSE .env.example
git commit -m "docs: 补 LICENSE 与 .env.example

README 一直声明 MIT 但仓库没有许可证文件，声明落空。

.env.example 里 AI_BASE_URL 显式带 /v1 并写明原因——旧 README 的示例缺 /v1，
照抄的人会对话/摄取/检索全线 404，这是本次要闭合的核心错误。"
```

---

## Task 6: 容器化产物（Dockerfile ×2 + nginx.conf + compose.prod）

**Files:**
- Create: `backend/Dockerfile`
- Create: `frontend/Dockerfile`
- Create: `frontend/nginx.conf`
- Create: `docker/docker-compose.prod.yml`
- Modify: `.gitignore`（若需忽略 `.env`——已有，确认即可）

**Interfaces:**
- Consumes: Task 5 的 `.env.example` 变量名
- Produces: 镜像 `qingyu-care-backend`、`qingyu-care-frontend`；compose 服务名 `postgres` / `redis` / `rustfs` / `backend` / `frontend`

- [ ] **Step 1: 创建后端 Dockerfile**

创建 `backend/Dockerfile`：

```dockerfile
# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

# 先只拷 pom 并预拉依赖：源码变动时这一层仍能命中缓存，重建快很多
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
# 跳过测试：镜像构建环境没有 Docker，集成测试（Testcontainers）跑不起来；
# 测试应在 CI 或本地 mvn verify 阶段把关，不在打包阶段
RUN mvn -B clean package -DskipTests

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app

# 非 root 运行
RUN groupadd -r qingyu && useradd -r -g qingyu qingyu

COPY --from=builder /build/target/*.jar app.jar
RUN chown -R qingyu:qingyu /app
USER qingyu

EXPOSE 8080

# 容器内健康检查，配合 compose 的 depends_on: condition: service_healthy
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD ["sh", "-c", "curl -fsS http://localhost:8080/api/actuator/health || exit 1"]

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
```

⚠️ `eclipse-temurin:17-jre` 可能不含 `curl`。Step 5 验证时若 HEALTHCHECK 一直 unhealthy，改用 Java 自带方式或在运行阶段 `apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*`。

- [ ] **Step 2: 创建前端 nginx 配置**

创建 `frontend/nginx.conf`：

```nginx
server {
    listen 80;
    server_name _;

    root /usr/share/nginx/html;
    index index.html;

    gzip on;
    gzip_types text/css application/javascript application/json image/svg+xml;
    gzip_min_length 1024;

    # 带内容哈希的构建产物可长缓存
    location /assets/ {
        expires 1y;
        add_header Cache-Control "public, immutable";
    }

    # widget.js 由后端作为静态资源提供，不能被 SPA fallback 吃掉
    location = /api/widget.js {
        proxy_pass http://backend:8080/api/widget.js;
    }

    location /api/ {
        proxy_pass http://backend:8080/api/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # ⚠️ SSE 必需：流式对话与坐席实时通道都走 EventStream。
        # 开着 buffering 会让 nginx 攒够缓冲区才下发，表现为「回复不逐字出现、
        # 整段一次性刷出」，而没有任何报错——排查时极难联想到反代配置。
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 3600s;
        chunked_transfer_encoding on;
    }

    # SPA history fallback：前端用 React Router，刷新子路由必须回落到 index.html
    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

- [ ] **Step 3: 创建前端 Dockerfile**

创建 `frontend/Dockerfile`：

```dockerfile
# ---------- 构建阶段 ----------
FROM node:20-alpine AS builder
WORKDIR /build

RUN corepack enable

# 先拷锁文件装依赖，命中缓存
COPY package.json pnpm-lock.yaml* ./
RUN pnpm install --frozen-lockfile

COPY . .
RUN pnpm build

# ---------- 运行阶段 ----------
FROM nginx:alpine
COPY --from=builder /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
```

⚠️ 若 `pnpm-lock.yaml` 不存在，`--frozen-lockfile` 会失败——先确认 `ls frontend/pnpm-lock.yaml`，不存在则去掉该参数。

- [ ] **Step 4: 创建生产 compose**

创建 `docker/docker-compose.prod.yml`：

```yaml
# 轻语（QingYu Care）生产环境编排
# 用法：
#   cp .env.example .env && 编辑 .env 填入真实值
#   docker compose -f docker/docker-compose.prod.yml --env-file .env up -d --build
#
# ⚠️ 上线前务必逐条过一遍 docs/部署文档.md 的「生产环境安全与调优清单」

services:
  postgres:
    image: qingyu/pgvector-zhparser:pg17
    build:
      context: .
      dockerfile: Dockerfile.pgvector-zhparser
    container_name: qingyu-postgres-prod
    environment:
      POSTGRES_DB: ${POSTGRES_DB}
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    volumes:
      - pg_data:/var/lib/postgresql/data
      - ./init.sql:/docker-entrypoint-initdb.d/init.sql:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}"]
      interval: 10s
      timeout: 5s
      retries: 5
    restart: unless-stopped
    # 生产不对外暴露数据库端口，仅容器网络内可达

  redis:
    image: redis:7-alpine
    container_name: qingyu-redis-prod
    command: ["redis-server", "--appendonly", "yes"]
    volumes:
      - redis_data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5
    restart: unless-stopped

  rustfs:
    image: rustfs/rustfs:latest
    container_name: qingyu-rustfs-prod
    environment:
      RUSTFS_ACCESS_KEY: ${RUSTFS_ACCESS_KEY}
      RUSTFS_SECRET_KEY: ${RUSTFS_SECRET_KEY}
    volumes:
      - rustfs_data:/data
    restart: unless-stopped

  backend:
    build:
      context: ../backend
      dockerfile: Dockerfile
    image: qingyu-care-backend:latest
    container_name: qingyu-backend-prod
    environment:
      SPRING_PROFILES_ACTIVE: prod
      DASHSCOPE_API_KEY: ${DASHSCOPE_API_KEY}
      AI_BASE_URL: ${AI_BASE_URL}
      AI_CHAT_MODEL: ${AI_CHAT_MODEL}
      AI_EMBEDDING_MODEL: ${AI_EMBEDDING_MODEL}
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB}
      SPRING_DATASOURCE_USERNAME: ${POSTGRES_USER}
      SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD}
      SPRING_DATA_REDIS_HOST: redis
      SPRING_DATA_REDIS_PORT: 6379
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
    restart: unless-stopped

  frontend:
    build:
      context: ../frontend
      dockerfile: Dockerfile
    image: qingyu-care-frontend:latest
    container_name: qingyu-frontend-prod
    ports:
      - "${FRONTEND_PORT:-80}:80"
    depends_on:
      - backend
    restart: unless-stopped

volumes:
  pg_data:
  redis_data:
  rustfs_data:
```

⚠️ 上面 postgres 的 `image` / `build` / volume 名与 `init.sql` 路径，必须与现有 `docker/docker-compose.yml` 保持一致——**动手前先 `cat docker/docker-compose.yml`，以它的实际写法为准**，不要照抄本 plan 的猜测值。RustFS 的镜像名与环境变量同理。

- [ ] **Step 5: 实际构建并验证起得来（本 Task 的核心验收）**

```bash
cd D:/Code/m/qingyu-care
cp .env.example .env
# 编辑 .env 填入真实 DASHSCOPE_API_KEY

# 先停掉开发环境，避免端口冲突
docker compose -f docker/docker-compose.yml down

docker compose -f docker/docker-compose.prod.yml --env-file .env build
```
Expected: 两个镜像都 build 成功。

```bash
docker compose -f docker/docker-compose.prod.yml --env-file .env up -d
docker compose -f docker/docker-compose.prod.yml ps
```
Expected: 五个服务全部 Up，backend 最终 healthy。

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost/           # 前端 → 200
curl -s -o /dev/null -w "%{http_code}\n" http://localhost/api/actuator/health   # 经 nginx 反代 → 200
```

验证 SPA fallback：浏览器直接打开 `http://localhost/knowledge` 并刷新，Expected: 正常渲染而非 404。

验完收拾干净并恢复开发环境：
```bash
docker compose -f docker/docker-compose.prod.yml --env-file .env down
docker compose -f docker/docker-compose.yml up -d
```

- [ ] **Step 6: 提交**

```bash
git add backend/Dockerfile frontend/Dockerfile frontend/nginx.conf docker/docker-compose.prod.yml
git commit -m "feat(deploy): 生产容器化产物——两个 Dockerfile + nginx + compose

多阶段构建，后端非 root 运行并带健康检查，前端 nginx 托管。

nginx 里 proxy_buffering off 不是可选项：流式对话与坐席实时通道都走 SSE，
开着 buffering 会让 nginx 攒够缓冲区才下发，表现为「回复不逐字出现、整段
一次性刷出」且没有任何报错，排查时极难联想到反代配置。

已实际 build 并起过一轮验证，不是只写在文档里。"
```

---

## Task 7: README 重写

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: Task 4 的 `docs/images/*.png`；Task 5 的 `.env.example`；Task 6 的 compose

- [ ] **Step 1: 现读现填版本号**

Run:
```bash
grep -nE "<version>4|spring-ai.version|sa-token-spring-boot4-starter" -A1 backend/pom.xml | head -20
node -e "const p=require('./frontend/package.json');console.log(p.dependencies.react,p.devDependencies.vite,p.devDependencies.typescript,p.devDependencies.tailwindcss)"
```

把结果记下来。**技术栈表里每一行都从这里填，不要沿用旧表的任何一行**——旧表已被证实至少三处失真（Boot 3.4.1→4.1.0、Spring AI 1.0.0-M4→2.0.0、Sa-Token 1.38.0→1.45.0），逐条核对比挑错改可靠。

已知正确值：Spring Boot **4.1.0**、Spring AI **2.0.0**、Sa-Token **1.45.0**、JDK **17**、Jsoup **1.23.1**、X File Storage **2.3.0**、React **19**、Vite **7**、TypeScript **5.9**、Tailwind **4**。

- [ ] **Step 2: 重写 README**

保留原骨架，逐节改写。必须落实的六点：

1. **技术栈表**用 Step 1 的实测值。
2. **`AI_BASE_URL` 示例改成 `https://dashscope.aliyuncs.com/compatible-mode/v1`**，并紧跟一段警示：
   > ⚠️ **`AI_BASE_URL` 必须自带 `/v1`**。Spring AI 2.0 起改用官方 openai-java SDK，只往 baseUrl 后拼 `chat/completions` 与 `embeddings`，不再补 `/v1`。缺 `/v1` 会让对话、文档摄取、知识检索全线 404。
3. **功能特性补齐六项**（原 README 完全没提）：
   - 网站知识库：URL 抓取正文入库 + 周期同步，内容未变不重嵌
   - 站点爬取：按范围前缀 BFS 多页爬取，遵守 robots.txt 与限速
   - 结构化卡片：LLM 内联 ```spec 围栏产出生成式 UI，两端纯 DOM 渲染
   - 多媒体消息：访客与坐席互发图片/文件，私有桶经鉴权代理端点下发
   - 工具调用与 Skill：HTTP 工具接入，SSRF 硬门槛
   - 流式对话：SSE 逐字输出，卡片在围栏闭合瞬间整张渲染
4. **截图墙**：README 顶部功能简介后放 `02-dashboard.png` 与 `14-widget-chat.png`；功能特性区穿插 `03` / `05` / `10` / `11` / `12`。用相对路径 `./docs/images/xx.png`。
5. **快速开始加一条演示数据**：
   ```bash
   # 想直接看效果？带 demo profile 启动，自动播种一整套演示数据
   mvn spring-boot:run -Dspring-boot.run.profiles=dev,demo
   # 演示账号：demo_admin / demo123456
   ```
6. **文档表指向 `docs/README.md`**，并新增使用手册与 LICENSE 行。

- [ ] **Step 3: 逐条验证 README 里的每个命令与链接**

- 所有 `./docs/xxx.md` 相对链接：`ls` 确认文件存在
- 所有 `./docs/images/xx.png`：`ls docs/images/` 确认存在
- 快速开始的每条命令亲自跑一遍（docker compose up、mvn spring-boot:run、pnpm dev）
- Swagger / actuator 两个 URL 用 curl 确认 200

- [ ] **Step 4: 提交**

```bash
git add README.md
git commit -m "docs: README 重写——订正失真版本号与致命的 AI_BASE_URL 示例

旧 README 停在两个大版本前（Boot 3.4.1 / Spring AI 1.0.0-M4 / Sa-Token 1.38.0，
实际是 4.1.0 / 2.0.0 / 1.45.0），且第 126 行示例 AI_BASE_URL 缺 /v1——Spring AI 2.0
起不再补，照抄的人对话/摄取/检索会全线 404。这是本次唯一一条会直接决定读者
能否跑起来的修改。

补齐六个已交付却在 README 里完全不可见的特性（网站知识库、站点爬取、结构化
卡片、多媒体消息、工具调用/Skill、流式对话），加实拍截图墙，快速开始补 demo
profile 一键演示数据。"
```

---

## Task 8: 使用手册 + 部署文档升级

**Files:**
- Create: `docs/使用手册.md`
- Modify: `docs/部署文档.md`

**Interfaces:**
- Consumes: Task 4 截图、Task 6 的 compose 与 nginx 配置

- [ ] **Step 1: 写 docs/使用手册.md**

面向租户管理员，12 节，每节配对应截图。内容要求：

1. **登录与角色**——三种角色（平台管理员/租户管理员/租户成员）各能看到哪些菜单。配 `01-login.png`。
2. **创建应用与获取凭据**——appKey/appSecret 的用途区别（服务端用 secret，浏览器 Widget 用 Origin 白名单），`allowedOrigins` 怎么填。
3. **建知识库与上传文档**——支持格式、切分参数含义、处理状态流转（PENDING→PROCESSING→COMPLETED）。配 `03` / `04`。
4. **网站知识库**——单页添加与站点爬取的区别；范围前缀、正文选择器、最大页数/深度各自的实际效果；robots.txt 与限速的含义。配 `05-crawl-site.png`。⚠️ 写清「噪声剔除只认语义标签，用 `<div>` 做导航的站点需要配自定义选择器」这个已知局限。
5. **QA 问答对**——整条 Q+A 嵌成单向量，适合高频短问题。
6. **问答策略**——TopK、相似度阈值、兜底话术三个参数怎么调，调高调低各自的症状。配 `06-strategy.png`。
7. **Prompt 模板**——版本管理与激活/回滚。配 `07-prompt.png`。
8. **模型配置与路由**——多提供商、按场景优先级、降级。⚠️ 写明 endpoint 必须带 `/v1`。配 `08-model-config.png`。
9. **转人工与工作台**——关键词/低置信度/连续未命中三类触发；坐席接管流程。配 `10-agent-workbench.png`。
10. **嵌入 Widget**——给可直接运行的完整示例：
    ```html
    <script src="http://localhost:8080/api/widget.js?appKey=demo-cloudboat-shop"></script>
    ```
    并说明生产要把域名加进 `allowedOrigins`。配 `13-integration.png` 与 `14-widget-chat.png`。
11. **开放 API 调用**——curl 示例（`X-App-Id` + `X-Api-Key`），含 `/open/v1/chat/completions` 与 SSE `/open/v1/chat/stream`。⚠️ 说明 token 计量是开放 API 专属，管理后台对话不计量。
12. **用量、额度与分析**——用量看板、套餐额度（FREE/PRO/ENTERPRISE 与超额 403）、分析三件套、A/B 实验。配 `11` / `12`。

开头加一段「快速体验」：指向 demo profile 与演示账号。

- [ ] **Step 2: 升级 docs/部署文档.md**

在现有九节基础上补五块（保留原有内容，不要推倒重写）：

- **容器化部署（新增一节，放在「基础设施部署」之后）**：`docker-compose.prod.yml` 的完整使用流程，从 `cp .env.example .env` 到 `up -d --build` 到验证清单。
- **Nginx / HTTPS**：在现有 §5.2 Nginx 示例基础上补 HTTPS 证书配置，并**显式加上 `proxy_buffering off;` 与它的原因**——流式对话与坐席实时通道都走 SSE，开着 buffering 表现为「回复不逐字出现」且无报错。
- **备份与恢复演练**：现有 §8 只有备份命令，补一段**恢复验证**步骤（起一个临时库、`pg_restore`、跑一次检索确认向量可用）。⚠️ 强调向量数据在 `vector_store` 表内，逻辑备份已覆盖，但换 embedding 模型后旧向量不可用，需重嵌。
- **监控与告警**：actuator 端点清单；四个值得盯的指标——长任务池（`LONG_TASK_EXECUTOR`，固定 6 线程 + 500 有界队列）饱和度、Webhook 投递积压（`webhook_delivery` 中 PENDING 行数）、文档摄取失败率、开放 API 429 比例。
- **故障手册（新增一节）**：把静默失败模式翻译成运维视角，每条给「现象 → 查什么 → 怎么修」：
  1. 对话/检索全线 404 → 查 `AI_BASE_URL` 与库里 `ai_model_config.endpoint` 是否带 `/v1`
  2. 检索 0 命中但文档显示已完成 → 查 `document.tenant_id` 与 `vector_store.metadata->>'tenantId'` 是否为空
  3. 回复不逐字出现 → 查反代 `proxy_buffering`
  4. token 计量恒为 0 → 确认走的是开放 API 而非管理后台
  5. 审计日志与 Webhook 莫名延迟 → 查是否有长任务占满默认线程池
  6. 网页文档每轮重嵌烧钱 → 查 `contentHash` 是否落库
  7. Webhook 投递卡住不到 SUCCESS → 查是否有另一个实例共用 Redis 消费组抢走了消息

- [ ] **Step 3: 验证文档里的命令**

对部署文档新增部分的每条命令做一次实跑或至少语法检查（`docker compose config` 可校验 compose 文件）。

Run: `docker compose -f docker/docker-compose.prod.yml --env-file .env config > /dev/null && echo OK`
Expected: OK。

- [ ] **Step 4: 提交**

```bash
git add docs/使用手册.md docs/部署文档.md
git commit -m "docs: 新增使用手册 + 部署文档升级到生产级

使用手册 12 节配实拍截图，面向租户管理员从建应用到看分析走一遍。

部署文档补五块：容器化部署、HTTPS 与 proxy_buffering off、备份的恢复演练
（原来只有备份没有恢复）、监控指标、故障手册。

故障手册把 CLAUDE.md 里那些静默失败模式翻译成运维视角的「现象 → 查什么 →
怎么修」——这些故障的共同特征是不报错，运维照常看到绿灯，只有知道去查哪张表
才定位得到。"
```

---

## Task 9: 仓库整理

**Files:**
- Delete: `docs/architecture/`（含 html 与 json）
- Delete: `docs/名字.png`
- Create: `docs/README.md`
- Create: `docs/技术债清单.md`
- Modify: `SaaS平台规划.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: 删除生成物与孤儿文件**

先复核确实无引用：
```bash
grep -rn "architecture\|名字.png" --include=*.md --include=*.tsx --include=*.ts --include=*.java . | grep -v node_modules | grep -v "/target/" | grep -v "^./references"
```
Expected: 无有效引用（README 项目结构树里若提到 `docs/` 是泛指，不算引用）。

```bash
git rm -r docs/architecture
git rm docs/名字.png
```

- [ ] **Step 2: 把技术债清单拆成独立文件**

Run: `grep -n "^## 技术债清单" -A 200 SaaS平台规划.md | head -5` 确认起止行号。

新建 `docs/技术债清单.md`，把 `SaaS平台规划.md` 中 `## 技术债清单` 整节（含「会咬人的代码债」「测试覆盖缺口」两个子节）**原文搬过去**，顶部加：

```markdown
# 技术债清单

> 只记「已交付代码欠的账」，不记「还没做的功能」——后者在 [SaaS平台规划.md](../SaaS平台规划.md)。
> 清理后折叠保留原问题描述，便于回看当时的判断依据。
```

`SaaS平台规划.md` 原位置换成一行指针，**不复述任何条目内容**（复述就会产生第二份会漂移的文案，正是卡片协议白名单分裂三处那个病）：

```markdown
## 技术债清单

已迁至 [`docs/技术债清单.md`](docs/技术债清单.md)。
```

- [ ] **Step 3: 建 docs/README.md 索引**

创建 `docs/README.md`：

```markdown
# 轻语文档索引

## 使用者该读的

| 文档 | 说明 |
|------|------|
| [使用手册](./使用手册.md) | 面向租户管理员：建应用 → 建知识库 → 调策略 → 嵌 Widget → 看分析 |
| [部署文档](./部署文档.md) | 从零部署、容器化、HTTPS、备份恢复、监控与故障手册 |

## 开发者该读的

| 文档 | 说明 |
|------|------|
| [开发流程文档](./开发流程文档.md) | 环境搭建、分支与提交规范、测试体系、多租户开发注意事项 |
| [技术债清单](./技术债清单.md) | 已交付代码欠的账 |
| [../CLAUDE.md](../CLAUDE.md) | 架构要点与踩坑记录（也是 AI 助手的项目指令） |
| [../SaaS平台规划.md](../SaaS平台规划.md) | 产品与技术路线图 |

## 开发过程存档

`superpowers/specs/` 与 `superpowers/plans/` 是各功能的设计文档与实现计划，
按 `YYYY-MM-DD-<主题>` 命名。**使用者不必阅读**——它们记录的是「当时为什么这么定」，
对排查历史决策有用，对使用产品没用。

`images/` 是 README 与使用手册引用的截图，基于 demo profile 演示数据实拍。
```

- [ ] **Step 4: 更新 CLAUDE.md**

在「常用命令 → 后端」一节补 demo profile 用法：

```markdown
mvn spring-boot:run -Dspring-boot.run.profiles=dev,demo   # 带演示数据启动（DemoDataSeeder）
```

并在合适位置加一小节，说明演示数据与截图的重拍方式：

```markdown
### 演示数据与截图

`DemoDataSeeder`（`@Profile("demo")`）播种「云舟科技」整套演示数据：租户/应用/用户、
知识库 4 篇文档（走真实 `uploadDocument()`，有真实向量）、8 条 QA、12 组会话（铺在最近
30 天）、30 天用量、分析日表经 `backfill` 真实聚合。幂等键是租户 code `demo-cloudboat`，
反复重启不重播。演示账号 `demo_admin` / `demo123456`。

⚠️ 两处坑已在类注释里写明，改动时别退化：**`ApplicationRunner` 没有 `TenantContext`**，
不手工搬运则文档 `tenant_id` 静默落 NULL（后果是对任何租户都检索不到）；
**`ChatSession`/`ChatMessage` 的 `createdAt` 带 `@CreationTimestamp`**，Hibernate 在 INSERT
时覆盖手工赋值，必须落库后用原生 UPDATE 回填，否则 30 天分析曲线退化成一根柱子。

`docs/images/` 的 14 张截图基于这套数据实拍（1440×900）。改了界面要重拍时：起
`dev,demo` profile，用 `demo_admin` 登录并**在顶部选中「云舟商城客服」应用**（应用级配置页
外包 `<RequireApp>`，未选应用截出来是空白），按 `docs/images/` 的编号顺序走一遍。
```

- [ ] **Step 5: 验证脚本与引用未被打破**

Run: `./scripts/manual-smoke-hints.sh`
Expected: 正常输出，exit 0。该脚本会校验它引用的 CLAUDE.md 章节标题仍存在——本 Task 只增章节不改既有标题，应当无影响。若 exit 1，说明动到了它引用的标题，需同步修脚本。

Run: `grep -rn "docs/architecture\|名字.png" --include=*.md . | grep -v references`
Expected: 无输出。

- [ ] **Step 6: 全量回归**

```bash
cd backend && mvn test
cd ../frontend && pnpm test && pnpm lint
cd ../widget && pnpm test
```
Expected: 全绿。特别是 `widget` 的 `buildFreshness.test.ts`——本次没改 `widget/src/`，应当绿。

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "chore: 仓库整理——删生成物、拆技术债清单、建文档索引

· 删 docs/architecture/（571KB 生成物 html + json，零引用）与 docs/名字.png（孤儿）
· 技术债清单从 SaaS平台规划.md 拆成 docs/技术债清单.md——路线图与欠账是两种读法、
  两种更新节奏，混在一个文件里两边都难读。原位置只留指针不复述内容，复述会产生
  第二份会漂移的文案。
· 新增 docs/README.md 索引，明确区分「使用者该读的」与「开发过程存档」
· CLAUDE.md 补 demo profile 用法与截图重拍步骤，两处静默失败的坑写进去

docs/superpowers/ 保持原地：迁移要同步改 10 处引用（含两条带章节号的指针），
且它是 brainstorming skill 的默认写入路径，改名后目录会立刻分裂成两份。"
```

---

## 收尾：分支合并

- [ ] **Step 1: 通读全分支 diff**

Run: `git diff master...HEAD --stat`
逐个文件确认没有误提交（`.env`、`target/`、`node_modules/`、截图里的敏感信息）。

- [ ] **Step 2: 按点名脚本走手工验证**

Run: `./scripts/manual-smoke-hints.sh master...HEAD`
按脚本点名的章节逐条验证。

- [ ] **Step 3: 请用户确认后合并**

不自行合并，先把分支改动摘要交给用户。合并方式与是否 push 由用户决定。

---

## Self-Review

**1. Spec coverage**

| Spec 要求 | 对应 Task |
|-----------|-----------|
| §3 演示数据（profile/上下文/幂等/内容/等待） | Task 1、2、3 |
| §4 截图 14 张 + 脱敏 | Task 4 |
| §5.1 README 重写 | Task 7 |
| §5.2 使用手册 | Task 8 |
| §5.3 部署文档升级 | Task 8 |
| §5.4 容器化产物 | Task 6 |
| §5.5 LICENSE + .env.example | Task 5 |
| §6.1 删生成物/拆技术债/建索引 | Task 9 |
| §6.2 superpowers 不迁移 | Task 9 Step 7 commit message 记录理由 |
| §7 验收 6 条 | Task 2 Step 5/6、Task 3 Step 5、Task 6 Step 5、Task 7 Step 3、Task 9 Step 6 |

无遗漏。

**2. Placeholder scan**：无 TBD/TODO；每个代码步骤都给了完整可粘贴的代码；两处标注「以实际为准」（compose 服务定义、前端路由表）是**刻意的**——它们依赖现有文件的实际写法，plan 里给猜测值反而有害，故明确要求先读现有文件。

**3. Type consistency**：`DemoDataContent.DemoDoc(filename, markdown)` 在 Task 2 定义、Task 2 使用；`DemoConversation(title, tag, turns)` 在 Task 3 定义并使用；`seedKnowledge()` 在 Task 2 定义、Task 3 的 `seed()` 中调用；`InMemoryMultipartFile` 仅 Task 2 内部使用。`DEMO_APP_KEY` 常量值 `demo-cloudboat-shop` 在 Task 1 定义，Task 4/7/8 的文档与截图步骤中一致引用。
