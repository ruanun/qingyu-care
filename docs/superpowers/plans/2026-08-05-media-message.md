# 多媒体消息（图片 / 文件）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户（Widget）与坐席（工作台）可以互发图片与文件，附件经后端代理端点访问。

**Architecture:** 新建 `ChatAttachment` 表（继承 `BaseTenantEntity` 自动获得两级租户隔离），上传与发消息分两步——先 `POST` 上传拿 `id`，再发消息带 `attachmentIds`。附件存私有 RustFS 桶，客户端一律经 `GET /api/chat/files/{id}`（坐席）或 `GET /open/v1/chat/files/{id}`（Widget）读取，鉴权后校验归属再流式返回。

**Tech Stack:** Spring Boot 4.1 / JPA / Apache Tika（MIME 探测，随 `spring-ai-tika-document-reader` 传递引入）/ JDK 自带 `ImageIO`（读图片尺寸）/ x-file-storage + AWS SDK v2（RustFS）/ React 19 / 原生 DOM（widget）

## Global Constraints

- 全部使用简体中文：对话、代码注释、commit message。新建文件的 `@author` 写 `ruan`。
- **图片上限 5MB，文件上限 20MB**。限额按**服务端探测出的真实类型**判定，不按客户端声明。
- **图片扩展名白名单**：`png` `jpg` `jpeg` `webp` `gif`。**文件扩展名白名单**：`pdf` `doc` `docx` `xls` `xlsx` `txt` `zip`。
- **SVG 不进白名单**（XSS 载体，且附件从本域下发）。
- 扩展名与 Tika 探测出的真实 MIME **必须一致**，不一致即拒。
- 图片响应 `Content-Disposition: inline`，**其余一律 `attachment`**。
- **不要新增显式 Tika 依赖**——它随 `spring-ai-tika-document-reader` 传递引入，`pom.xml:126` 的注释记着当年显式 pin 版本反而压回错配版本的教训。
- **不要修改 `application.yml` 的 `x-file-storage` `base-path: knowledge/`**——存量知识库文件的完整 URL 已存进 `document.file_path`，改了全部读不到。聊天附件用 `chat/` 前缀，实际对象键是 `knowledge/chat/yyyy/MM/dd/`，路径名不好看但**不要动**。
- 新增测试优先写成纯 Mockito 单测（`mvn test`，免 Docker）；只有「必须真实 HTTP 请求流经拦截器才生效」的才写成 `*IT`（`mvn verify`，需 Docker）。
- ⚠️ **跑 `mvn verify` 前必须先停掉本机 `:8080` 的 dev 后端**，否则 `WebhookDeliveryIT` 会因共用 Redis 消费组而必红（与本特性无关，见 CLAUDE.md）。
- 前端用 pnpm。widget 改动后必须 `pnpm build` 重新产出 `backend/src/main/resources/static/widget.js` 并提交。

---

## 文件结构

**后端新建**
| 文件 | 职责 |
|---|---|
| `entity/ChatAttachment.java` | 附件实体，继承 `BaseTenantEntity` |
| `repository/ChatAttachmentRepository.java` | 含批量查询与孤儿查询 |
| `service/media/AttachmentValidator.java` | **不碰数据库、纯可测**：大小/扩展名/真实 MIME/尺寸探测 |
| `service/media/ChatAttachmentService.java` | 编排：校验 → 存储 → 落库；归属校验；孤儿清理 |
| `scheduler/AttachmentCleanupScheduler.java` | 孤儿文件定时清理 |
| `dto/AttachmentDTO.java` | 出参，含按来源生成的 `url` |
| `dto/ChatMessageDTO.java` | 历史消息出参（现在直接返回实体，加不了附件） |

**后端修改**
| 文件 | 改动 |
|---|---|
| `service/FileService.java` | 加 `uploadChatFile` 与流式 `openStream` |
| `controller/ChatController.java` | 加上传/下载端点；`getSessionMessages` 改返回 DTO；`agentReply` 收 `attachmentIds` |
| `controller/open/OpenChatController.java` | 加上传/下载端点 |
| `dto/ChatRequest.java` / `dto/open/OpenChatRequest.java` | 加 `attachmentIds` |
| `service/impl/ChatServiceImpl.java` | 保存消息后回填附件的 `messageId` |
| `service/realtime/RealtimeNotifier.java` | `messageJson` 补 `attachments` 与 `uiSpec` |

**前端 / widget**
| 文件 | 改动 |
|---|---|
| `frontend/src/components/chat/Attachments.tsx` | 新建，附件渲染（**不复用卡片 image 组件**） |
| `frontend/src/pages/ChatPage.tsx` | 附件渲染 + 上传控件 |
| `frontend/src/pages/AgentPage.tsx` | 接入 `JsonRender` + 附件渲染 + 上传控件 |
| `frontend/src/api/chat.ts` `types/index.ts` | 上传 API 与类型 |
| `widget/src/attachments.ts` | 新建，纯 DOM 附件渲染 |
| `widget/src/ui.ts` `api.ts` | 上传控件与 API |

---

### Task 1: ChatAttachment 实体与仓储

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/entity/ChatAttachment.java`
- Create: `backend/src/main/java/com/qingyu/care/repository/ChatAttachmentRepository.java`
- Test: `backend/src/test/java/com/qingyu/care/entity/ChatAttachmentTest.java`

**Interfaces:**
- Produces: `ChatAttachment`（Lombok `@Builder`），字段见下；`ChatAttachmentRepository.findByMessageIdIn(Collection<Long>)`、`findByMessageIdIsNullAndCreatedAtBefore(LocalDateTime)`

- [ ] **Step 1: 写实体**

⚠️ `@Builder` 会**忽略字段初始值**，凡是带初值的字段必须加 `@Builder.Default`——本仓库在 `ChatSession.noHitStreak` 上踩过这个坑导致 NPE。本实体没有带初值的字段，但如果你要加，记住这条。

```java
package com.qingyu.care.entity;

import com.qingyu.care.enums.MessageRole;
import jakarta.persistence.*;
import lombok.*;

/**
 * 聊天消息附件。上传与发消息分两步，故 {@code messageId} 可空——
 * 上传时为 null，发消息时才回填；始终为 null 的是「传了没发」的孤儿，由定时任务清理。
 *
 * @author ruan
 */
@Entity
@Table(name = "chat_attachment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatAttachment extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属消息。上传时为 null，发消息时回填 */
    @Column(name = "message_id")
    private Long messageId;

    /** 会话业务标识（ChatSession.sessionId，不是主键 id），上传时即绑定 */
    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    /** 原始文件名，展示用——渲染时必须转义 */
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    /** 服务端探测出的真实 MIME，不是客户端传的 Content-Type */
    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    /** S3 对象键。存 key 不存 URL——裸 URL 客户端用不了，存了是误导 */
    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    /** 仅图片，供前端预留占位避免加载时布局跳动 */
    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Enumerated(EnumType.STRING)
    @Column(name = "uploader_role", nullable = false, length = 20)
    private MessageRole uploaderRole;
}
```

- [ ] **Step 2: 写仓储**

```java
package com.qingyu.care.repository;

import com.qingyu.care.entity.ChatAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * @author ruan
 */
public interface ChatAttachmentRepository extends JpaRepository<ChatAttachment, Long> {

    /** 批量取消息的附件——历史消息一次查完，避免逐条 LAZY 的 N+1 */
    List<ChatAttachment> findByMessageIdIn(Collection<Long> messageIds);

    /** 孤儿：始终没被任何消息引用、且早于给定时间创建 */
    List<ChatAttachment> findByMessageIdIsNullAndCreatedAtBefore(LocalDateTime before);
}
```

- [ ] **Step 3: 写测试验证 Builder 与继承**

```java
package com.qingyu.care.entity;

import com.qingyu.care.enums.MessageRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author ruan
 */
class ChatAttachmentTest {

    @Test
    void builder_构造出的实体字段齐全且继承租户基类() {
        ChatAttachment a = ChatAttachment.builder()
                .sessionId("s-1")
                .fileName("截图.png")
                .fileSize(1024L)
                .contentType("image/png")
                .storageKey("knowledge/chat/2026/08/05/ab12_截图.png")
                .width(800)
                .height(600)
                .uploaderRole(MessageRole.USER)
                .build();

        assertThat(a).isInstanceOf(BaseTenantEntity.class);
        assertThat(a.getMessageId()).isNull();   // 上传时未关联消息
        assertThat(a.getFileName()).isEqualTo("截图.png");
        assertThat(a.getWidth()).isEqualTo(800);
        assertThat(a.getUploaderRole()).isEqualTo(MessageRole.USER);
    }
}
```

- [ ] **Step 4: 跑测试**

Run: `cd backend && mvn test -Dtest=ChatAttachmentTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/entity/ChatAttachment.java backend/src/main/java/com/qingyu/care/repository/ChatAttachmentRepository.java backend/src/test/java/com/qingyu/care/entity/ChatAttachmentTest.java
git commit -m "feat(backend): 聊天附件实体与仓储"
```

---

### Task 2: 上传校验器（纯组件，本特性的安全核心）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/media/AttachmentValidator.java`
- Test: `backend/src/test/java/com/qingyu/care/service/media/AttachmentValidatorTest.java`

**Interfaces:**
- Produces: `AttachmentValidator.validate(byte[] bytes, String originalFilename) : ValidatedFile`，`record ValidatedFile(String contentType, boolean image, Integer width, Integer height)`；校验失败抛 `BusinessException`

- [ ] **Step 1: 写失败测试**

这些用例是本特性最重要的防线。每一条对应一种真实攻击或误用。

```java
package com.qingyu.care.service.media;

import com.qingyu.care.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author ruan
 */
class AttachmentValidatorTest {

    private final AttachmentValidator validator = new AttachmentValidator();

    /** 造一张真实的 PNG，让 Tika 能探测出 image/png、ImageIO 能读出尺寸 */
    private static byte[] pngBytes(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void 合法PNG通过并读出宽高() throws Exception {
        AttachmentValidator.ValidatedFile r = validator.validate(pngBytes(800, 600), "截图.png");

        assertThat(r.contentType()).isEqualTo("image/png");
        assertThat(r.image()).isTrue();
        assertThat(r.width()).isEqualTo(800);
        assertThat(r.height()).isEqualTo(600);
    }

    @Test
    void 可执行文件改名成jpg被拒() {
        // MZ 是 Windows PE 可执行文件的魔数，Tika 会探测成 application/x-msdownload
        byte[] exe = new byte[]{0x4D, 0x5A, (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00};

        assertThatThrownBy(() -> validator.validate(exe, "photo.jpg"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("文件内容与扩展名不符");
    }

    @Test
    void SVG被拒即便扩展名与内容一致() {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> validator.validate(svg, "icon.svg"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的文件类型");
    }

    @Test
    void 图片超过5MB被拒() throws Exception {
        // 用一张大图撑过 5MB；随机噪点让 PNG 压不动
        BufferedImage img = new BufferedImage(2000, 2000, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 2000; x++) {
            for (int y = 0; y < 2000; y++) {
                img.setRGB(x, y, (x * 7919 + y * 104729) & 0xFFFFFF);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        byte[] big = out.toByteArray();
        assertThat(big.length).isGreaterThan(5 * 1024 * 1024);

        assertThatThrownBy(() -> validator.validate(big, "big.png"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("图片不能超过");
    }

    @Test
    void 非白名单扩展名被拒() {
        byte[] sh = "#!/bin/sh\necho hi\n".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> validator.validate(sh, "run.sh"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的文件类型");
    }

    @Test
    void 无扩展名被拒() {
        assertThatThrownBy(() -> validator.validate("hello".getBytes(StandardCharsets.UTF_8), "noext"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的文件类型");
    }

    @Test
    void 文本文件按文件类通过且不带宽高() {
        AttachmentValidator.ValidatedFile r =
                validator.validate("订单号 12345".getBytes(StandardCharsets.UTF_8), "备注.txt");

        assertThat(r.image()).isFalse();
        assertThat(r.width()).isNull();
        assertThat(r.height()).isNull();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && mvn test -Dtest=AttachmentValidatorTest`
Expected: FAIL，编译错误 `找不到符号 AttachmentValidator`

- [ ] **Step 3: 写实现**

```java
package com.qingyu.care.service.media;

import com.qingyu.care.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 附件校验：大小、扩展名、真实类型、图片尺寸。不碰数据库、不碰存储，纯可测。
 *
 * <p>四道校验顺序不可换：先按类型分别限额 → 扩展名白名单 → Tika 探测真实类型并与扩展名比对
 * → 图片再读尺寸。第三道是关键——只信客户端传的 Content-Type 等于没校验，把 .exe 改名成
 * .jpg 是最基本的绕过手法。
 *
 * <p>SVG 不在白名单里：它是 XSS 载体，浏览器会执行其中的 script，而附件是从本域下发的。
 *
 * @author ruan
 */
@Component
@Slf4j
public class AttachmentValidator {

    /** 图片上限 5MB */
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    /** 其它文件上限 20MB */
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    /** 扩展名 → 期望的真实 MIME。故意不含 svg */
    private static final Map<String, String> IMAGE_TYPES = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "webp", "image/webp",
            "gif", "image/gif");

    private static final Map<String, Set<String>> FILE_TYPES = Map.of(
            "pdf", Set.of("application/pdf"),
            "doc", Set.of("application/msword"),
            "docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            "xls", Set.of("application/vnd.ms-excel"),
            "xlsx", Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            "txt", Set.of("text/plain"),
            "zip", Set.of("application/zip", "application/x-zip-compressed"));

    private final Tika tika = new Tika();

    public record ValidatedFile(String contentType, boolean image, Integer width, Integer height) {}

    public ValidatedFile validate(byte[] bytes, String originalFilename) {
        String ext = extensionOf(originalFilename);
        boolean isImage = IMAGE_TYPES.containsKey(ext);
        boolean isFile = FILE_TYPES.containsKey(ext);

        if (!isImage && !isFile) {
            throw new BusinessException("不支持的文件类型：" + (ext.isEmpty() ? "（无扩展名）" : ext));
        }

        // 先卡大小：按扩展名归类即可，此刻还没探测真实类型；探测后若类别翻转会在下面再卡一次
        long limit = isImage ? MAX_IMAGE_BYTES : MAX_FILE_BYTES;
        requireWithinLimit(bytes.length, limit, isImage);

        String detected = tika.detect(bytes);

        if (isImage) {
            if (!IMAGE_TYPES.get(ext).equals(detected)) {
                throw new BusinessException("文件内容与扩展名不符");
            }
            Integer[] size = readImageSize(bytes);
            return new ValidatedFile(detected, true, size[0], size[1]);
        }

        if (!FILE_TYPES.get(ext).contains(detected)) {
            throw new BusinessException("文件内容与扩展名不符");
        }
        // 探测出来是图片却用了文件扩展名：按图片限额重卡一次，防止拿 .txt 扩展名绕过 5MB
        if (detected.startsWith("image/")) {
            requireWithinLimit(bytes.length, MAX_IMAGE_BYTES, true);
        }
        return new ValidatedFile(detected, false, null, null);
    }

    private void requireWithinLimit(long actual, long limit, boolean image) {
        if (actual > limit) {
            throw new BusinessException(
                    (image ? "图片不能超过 " : "文件不能超过 ") + (limit / 1024 / 1024) + "MB");
        }
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 读不出尺寸不算失败——宽高只用于前端预留占位，缺了顶多布局跳一下 */
    private Integer[] readImageSize(byte[] bytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img != null) {
                return new Integer[]{img.getWidth(), img.getHeight()};
            }
        } catch (Exception e) {
            log.warn("读取图片尺寸失败，跳过宽高: {}", e.getMessage());
        }
        return new Integer[]{null, null};
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd backend && mvn test -Dtest=AttachmentValidatorTest`
Expected: PASS（7 个用例）

- [ ] **Step 5: 变异自证**

把 `validate` 里 Tika 比对那两处 `throw` 注释掉，重跑：`可执行文件改名成jpg被拒` 必须转红。确认后还原。
把 `IMAGE_TYPES` 加一项 `"svg", "image/svg+xml"`，重跑：`SVG被拒即便扩展名与内容一致` 必须转红。确认后还原。

**在报告里写明这两次变异的结果。**绿本身证明不了这些防线有效。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/media/AttachmentValidator.java backend/src/test/java/com/qingyu/care/service/media/AttachmentValidatorTest.java
git commit -m "feat(backend): 附件校验器——大小/扩展名/真实MIME/尺寸

只信客户端 Content-Type 等于没校验，故用 Tika 探测真实类型与扩展名比对。
SVG 刻意不进白名单：它是 XSS 载体，而附件是从本域下发的。"
```

---

### Task 3: FileService 扩展（聊天附件上传与流式读取）

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/FileService.java`
- Test: `backend/src/test/java/com/qingyu/care/service/FileServiceChatTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `FileService.uploadChatBytes(byte[] bytes, String originalFilename, String contentType) : String`（返回 storageKey）；`FileService.openStream(String storageKey) : InputStream`

- [ ] **Step 1: 加两个方法**

在 `FileService` 里新增。注意 `generateFilePath` / `generateFileName` / `sanitizeFilename` 已存在，直接复用。

⚠️ 前缀用 `"chat"`，实际对象键会是 `knowledge/chat/yyyy/MM/dd/xxx`（`application.yml:204` 有全局 `base-path: knowledge/`）。**不要为了路径好看去改那个配置**——存量知识库文件的完整 URL 已存进 `document.file_path`，改了全部读不到。

```java
    /**
     * 上传聊天附件。与知识库文档分开放在 chat/ 前缀下（实际对象键带全局 base-path 前缀）。
     *
     * @return S3 对象键，供后续 openStream/deleteFile 使用
     */
    public String uploadChatBytes(byte[] bytes, String originalFilename, String contentType) {
        String path = generateFilePath("chat");
        String filename = generateFileName(originalFilename);

        FileInfo fileInfo = fileStorageService.of(bytes)
                .setPath(path)
                .setSaveFilename(filename)
                .setContentType(contentType)
                .upload();

        log.info("聊天附件上传成功: {} -> {}", originalFilename, fileInfo.getUrl());
        return fileInfo.getUrl();
    }

    /**
     * 流式读取对象。附件最大 20MB，用 byte[] 会让每个并发请求都吃满一份堆内存。
     */
    public InputStream openStream(String storageKey) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(storageKey)
                .build();
        return s3Client.getObject(request);
    }
```

需要新增的 import：`java.io.InputStream`、`software.amazon.awssdk.services.s3.model.GetObjectRequest`。

- [ ] **Step 2: 确认 storageKey 与 downloadFile 的口径一致**

`downloadFile(String filePath)` 的注释写着「filePath 已经是完整 S3 对象键」，而 `uploadKnowledgeFile` 返回的 `FileInfo.getUrl()` 被 `KnowledgeServiceImpl:141` 原样存进 `Document.filePath` 再交给 `downloadFile`。**照抄这个口径**：`uploadChatBytes` 同样返回 `fileInfo.getUrl()` 存进 `storageKey`，`openStream` 收到的就是它。

写一个测试把这个口径钉死，避免后人改成返回 path+filename 拼接：

```java
package com.qingyu.care.service;

import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.when;

/**
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileServiceChatTest {

    @Mock(answer = RETURNS_DEEP_STUBS)
    private FileStorageService fileStorageService;
    @Mock
    private S3Client s3Client;
    @InjectMocks
    private FileService fileService;

    @Test
    void 上传聊天附件返回的是可直接喂给openStream的对象键() {
        FileInfo info = new FileInfo();
        info.setUrl("knowledge/chat/2026/08/05/ab12_截图.png");
        when(fileStorageService.of(any(byte[].class))
                .setPath(any())
                .setSaveFilename(any())
                .setContentType(any())
                .upload()).thenReturn(info);

        String key = fileService.uploadChatBytes(new byte[]{1, 2, 3}, "截图.png", "image/png");

        // 与 downloadFile 的口径一致：存的是 FileInfo.getUrl()，不是自己拼的 path+filename
        assertThat(key).isEqualTo("knowledge/chat/2026/08/05/ab12_截图.png");
    }
}
```

- [ ] **Step 3: 跑测试**

Run: `cd backend && mvn test -Dtest=FileServiceChatTest`
Expected: PASS

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/FileService.java backend/src/test/java/com/qingyu/care/service/FileServiceChatTest.java
git commit -m "feat(backend): FileService 支持聊天附件上传与流式读取

附件最大 20MB，用 byte[] 会让每个并发请求吃满一份堆内存，故加 openStream。"
```

---

### Task 4: 附件服务（编排 + 归属校验 + 孤儿清理）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/media/ChatAttachmentService.java`
- Create: `backend/src/main/java/com/qingyu/care/scheduler/AttachmentCleanupScheduler.java`
- Modify: `backend/src/main/resources/application.yml`（清理间隔配置）
- Test: `backend/src/test/java/com/qingyu/care/service/media/ChatAttachmentServiceTest.java`

**Interfaces:**
- Consumes: `AttachmentValidator.validate(byte[], String)`、`FileService.uploadChatBytes(byte[], String, String)`、`ChatAttachmentRepository`
- Produces:
  - `ChatAttachmentService.upload(String sessionId, MultipartFile file, MessageRole uploaderRole) : ChatAttachment`
  - `ChatAttachmentService.attachToMessage(List<Long> attachmentIds, String sessionId, Long messageId) : void`
  - `ChatAttachmentService.getForRead(Long id) : ChatAttachment`
  - `ChatAttachmentService.cleanupOrphans(int hours) : int`

- [ ] **Step 1: 写失败测试**

`attachToMessage` 的三条拒绝是本任务的重点——不校验就能引用别人的附件 ID 把别家的图挂到自己消息上。

```java
package com.qingyu.care.service.media;

import com.qingyu.care.entity.ChatAttachment;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.ChatAttachmentRepository;
import com.qingyu.care.service.FileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatAttachmentServiceTest {

    @Mock
    private ChatAttachmentRepository attachmentRepository;
    @Mock
    private FileService fileService;
    @Mock
    private AttachmentValidator validator;
    @InjectMocks
    private ChatAttachmentService service;

    private static ChatAttachment attachment(Long id, String sessionId, Long messageId) {
        ChatAttachment a = ChatAttachment.builder()
                .sessionId(sessionId)
                .messageId(messageId)
                .fileName("a.png")
                .fileSize(10L)
                .contentType("image/png")
                .storageKey("k")
                .uploaderRole(MessageRole.USER)
                .build();
        a.setId(id);
        return a;
    }

    @Test
    void 关联消息时会话不匹配则拒绝() {
        when(attachmentRepository.findById(7L))
                .thenReturn(Optional.of(attachment(7L, "别人的会话", null)));

        assertThatThrownBy(() -> service.attachToMessage(List.of(7L), "我的会话", 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("附件不属于当前会话");

        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void 关联消息时附件已被别的消息占用则拒绝() {
        when(attachmentRepository.findById(7L))
                .thenReturn(Optional.of(attachment(7L, "s-1", 999L)));   // 已挂在 999 上

        assertThatThrownBy(() -> service.attachToMessage(List.of(7L), "s-1", 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("附件已被使用");

        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void 关联消息时附件不存在则拒绝() {
        when(attachmentRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.attachToMessage(List.of(7L), "s-1", 100L))
                .isInstanceOf(BusinessException.class);

        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void 合法附件被回填messageId() {
        ChatAttachment a = attachment(7L, "s-1", null);
        when(attachmentRepository.findById(7L)).thenReturn(Optional.of(a));

        service.attachToMessage(List.of(7L), "s-1", 100L);

        assertThat(a.getMessageId()).isEqualTo(100L);
        verify(attachmentRepository).save(a);
    }

    @Test
    void 附件为空列表时什么都不做() {
        service.attachToMessage(List.of(), "s-1", 100L);
        service.attachToMessage(null, "s-1", 100L);

        verifyNoInteractions(attachmentRepository);
    }

    @Test
    void 清理孤儿会同时删除S3对象而不是只删库行() {
        ChatAttachment orphan = attachment(1L, "s-1", null);
        orphan.setStorageKey("knowledge/chat/2026/08/01/x.png");
        when(attachmentRepository.findByMessageIdIsNullAndCreatedAtBefore(any()))
                .thenReturn(List.of(orphan));

        int n = service.cleanupOrphans(24);

        assertThat(n).isEqualTo(1);
        verify(fileService).deleteFile("knowledge/chat/2026/08/01/x.png");
        verify(attachmentRepository).delete(orphan);
    }

    @Test
    void 清理时单个S3删除失败不影响其余() {
        ChatAttachment bad = attachment(1L, "s-1", null);
        ChatAttachment good = attachment(2L, "s-1", null);
        when(attachmentRepository.findByMessageIdIsNullAndCreatedAtBefore(any()))
                .thenReturn(List.of(bad, good));
        doThrow(new RuntimeException("S3 挂了")).when(fileService).deleteFile(bad.getStorageKey());

        int n = service.cleanupOrphans(24);

        assertThat(n).isEqualTo(1);                  // 只算成功的
        verify(attachmentRepository).delete(good);   // 后一个照常清理
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && mvn test -Dtest=ChatAttachmentServiceTest`
Expected: FAIL，编译错误 `找不到符号 ChatAttachmentService`

- [ ] **Step 3: 写实现**

```java
package com.qingyu.care.service.media;

import com.qingyu.care.entity.ChatAttachment;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.ChatAttachmentRepository;
import com.qingyu.care.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 聊天附件编排：上传（校验 → 存储 → 落库）、关联消息、读取、孤儿清理。
 *
 * <p>上传与发消息分两步，故附件落库时 {@code messageId} 为 null，发消息时才回填。
 * 两个后果都在本类处理：{@link #attachToMessage} 做归属校验防越权引用，
 * {@link #cleanupOrphans} 清理「传了没发」的残留。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatAttachmentService {

    private final ChatAttachmentRepository attachmentRepository;
    private final FileService fileService;
    private final AttachmentValidator validator;

    @Transactional
    public ChatAttachment upload(String sessionId, MultipartFile file, MessageRole uploaderRole) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("读取上传文件失败");
        }

        String originalFilename = file.getOriginalFilename();
        AttachmentValidator.ValidatedFile v = validator.validate(bytes, originalFilename);
        String storageKey = fileService.uploadChatBytes(bytes, originalFilename, v.contentType());

        ChatAttachment attachment = ChatAttachment.builder()
                .sessionId(sessionId)
                .fileName(originalFilename)
                .fileSize((long) bytes.length)
                .contentType(v.contentType())
                .storageKey(storageKey)
                .width(v.width())
                .height(v.height())
                .uploaderRole(uploaderRole)
                .build();
        return attachmentRepository.save(attachment);
    }

    /**
     * 把附件挂到消息上。三道校验缺一不可——少了任何一道，
     * 攻击者都能遍历 id 把别人的附件挂到自己的消息上。
     *
     * <p>注意 findById 本身不受 Hibernate @Filter 保护（本仓库的成文教训），
     * 所以必须显式比对 sessionId，不能只靠"查得到就是我的"。
     */
    @Transactional
    public void attachToMessage(List<Long> attachmentIds, String sessionId, Long messageId) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return;
        }
        for (Long id : attachmentIds) {
            ChatAttachment a = attachmentRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("附件不存在: " + id));
            if (!sessionId.equals(a.getSessionId())) {
                throw new BusinessException("附件不属于当前会话");
            }
            if (a.getMessageId() != null) {
                throw new BusinessException("附件已被使用");
            }
            a.setMessageId(messageId);
            attachmentRepository.save(a);
        }
    }

    /** 读取用：归属校验交给调用方（两条访问端点的鉴权路径不同） */
    public ChatAttachment getForRead(Long id) {
        return attachmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("附件不存在: " + id));
    }

    /**
     * 清理孤儿附件：始终没被任何消息引用、且超过给定小时数的。
     * 单个失败不影响其余（fail-soft），返回真正清理成功的条数。
     *
     * <p>S3 对象必须一起删——只删库行会留下永久占用空间、且再也查不到的孤儿对象。
     */
    @Transactional
    public int cleanupOrphans(int hours) {
        List<ChatAttachment> orphans = attachmentRepository
                .findByMessageIdIsNullAndCreatedAtBefore(LocalDateTime.now().minusHours(hours));
        int cleaned = 0;
        for (ChatAttachment a : orphans) {
            try {
                fileService.deleteFile(a.getStorageKey());
                attachmentRepository.delete(a);
                cleaned++;
            } catch (Exception e) {
                log.warn("清理孤儿附件失败 id={} key={}: {}", a.getId(), a.getStorageKey(), e.getMessage());
            }
        }
        return cleaned;
    }
}
```

- [ ] **Step 4: 写调度器**

```java
package com.qingyu.care.scheduler;

import com.qingyu.care.service.media.ChatAttachmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 清理「传了但没发送」的孤儿附件。
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AttachmentCleanupScheduler {

    private final ChatAttachmentService chatAttachmentService;

    @Value("${qingyu.care.attachment.orphan-retain-hours:24}")
    private int retainHours;

    /** 每小时扫一次（首次延迟 10 分钟，避开启动高峰）；间隔外部化，仿本仓库其余 @Scheduled 写法 */
    @Scheduled(initialDelayString = "${qingyu.care.attachment.cleanup-initial-delay-ms:600000}",
               fixedDelayString = "${qingyu.care.attachment.cleanup-interval-ms:3600000}")
    public void cleanupOrphanAttachments() {
        try {
            int n = chatAttachmentService.cleanupOrphans(retainHours);
            if (n > 0) {
                log.info("孤儿附件清理完成：{} 个", n);
            }
        } catch (Exception e) {
            log.warn("孤儿附件清理意外失败: {}", e.getMessage());
        }
    }
}
```

在 `application.yml` 的 `qingyu.care` 节点下加配置（与既有 `web-sync` 同级）：

```yaml
    # 聊天附件
    attachment:
      # 「传了没发送」的孤儿保留时长（小时），超过即连同 S3 对象一起删
      orphan-retain-hours: 24
      # 清理任务首次延迟与间隔（毫秒）
      cleanup-initial-delay-ms: 600000
      cleanup-interval-ms: 3600000
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd backend && mvn test -Dtest=ChatAttachmentServiceTest`
Expected: PASS（7 个用例）

- [ ] **Step 6: 变异自证**

把 `attachToMessage` 里 `if (!sessionId.equals(a.getSessionId()))` 整段删掉，重跑：`关联消息时会话不匹配则拒绝` 必须转红。
把 `if (a.getMessageId() != null)` 整段删掉，重跑：`关联消息时附件已被别的消息占用则拒绝` 必须转红。
把 `cleanupOrphans` 里 `fileService.deleteFile(...)` 那行删掉，重跑：`清理孤儿会同时删除S3对象而不是只删库行` 必须转红。
三条都确认后还原，**在报告里写明结果**。

- [ ] **Step 7: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/media/ChatAttachmentService.java backend/src/main/java/com/qingyu/care/scheduler/AttachmentCleanupScheduler.java backend/src/main/resources/application.yml backend/src/test/java/com/qingyu/care/service/media/ChatAttachmentServiceTest.java
git commit -m "feat(backend): 附件编排服务与孤儿清理

attachToMessage 的三道校验缺一不可：不校验会话归属就能遍历 id 把别人的
附件挂到自己消息上。findById 不受 @Filter 保护，必须显式比对。
孤儿清理要连 S3 对象一起删——只删库行会留下再也查不到的占用。"
```

---

### Task 5: 四条端点（两条上传、两条访问）

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/dto/AttachmentDTO.java`
- Modify: `backend/src/main/java/com/qingyu/care/controller/ChatController.java`
- Modify: `backend/src/main/java/com/qingyu/care/controller/open/OpenChatController.java`
- Test: `backend/src/test/java/com/qingyu/care/it/ChatAttachmentIT.java`

**Interfaces:**
- Consumes: `ChatAttachmentService.upload(...)`、`getForRead(Long)`、`FileService.openStream(String)`
- Produces: `AttachmentDTO.of(ChatAttachment, String urlPrefix)`；端点 `POST /v1/chat/files`、`GET /v1/chat/files/{id}`、`POST /open/v1/chat/files`、`GET /open/v1/chat/files/{id}`

- [ ] **Step 1: 写 DTO**

⚠️ `url` **不存库**，按调用来源生成——管理端与 Widget 的鉴权路径不同，同一个附件的访问 URL 也不同。

```java
package com.qingyu.care.dto;

import com.qingyu.care.entity.ChatAttachment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 附件出参。{@code url} 由后端按调用来源拼装（管理端 /v1/chat/files/、Widget /open/v1/chat/files/），
 * 不存库——存了一个某一端用不了的地址只会误导。
 *
 * @author ruan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttachmentDTO {

    private Long id;
    private String fileName;
    private String contentType;
    private Long fileSize;
    private Integer width;
    private Integer height;
    /** 访问地址，形如 /api/v1/chat/files/12 */
    private String url;

    public static AttachmentDTO of(ChatAttachment a, String urlPrefix) {
        return AttachmentDTO.builder()
                .id(a.getId())
                .fileName(a.getFileName())
                .contentType(a.getContentType())
                .fileSize(a.getFileSize())
                .width(a.getWidth())
                .height(a.getHeight())
                .url(urlPrefix + a.getId())
                .build();
    }
}
```

- [ ] **Step 2: 管理端两条端点**

加进 `ChatController`（类上已有 `@RequestMapping("/v1/chat")`）。需要注入 `ChatAttachmentService` 与 `FileService`。

```java
    /** 管理端/坐席上传附件。上传与发消息分两步，这里只落库拿 id */
    @PostMapping("/files")
    @AuditLog(action = "CHAT_ATTACHMENT_UPLOAD", targetType = "ChatAttachment")
    public ApiResponse<AttachmentDTO> uploadAttachment(
            @RequestParam("sessionId") String sessionId,
            @RequestParam("file") MultipartFile file) {
        ChatAttachment a = chatAttachmentService.upload(sessionId, file, MessageRole.AGENT);
        return ApiResponse.success(AttachmentDTO.of(a, "/v1/chat/files/"));
    }

    /**
     * 读取附件。桶是私有的，客户端拿不到也不该拿到裸 S3 地址，一律经此代理。
     *
     * <p>图片 inline 供内嵌预览，其余一律 attachment 强制下载——不给浏览器渲染的机会。
     */
    @GetMapping("/files/{id}")
    public ResponseEntity<StreamingResponseBody> downloadAttachment(@PathVariable Long id) {
        ChatAttachment a = chatAttachmentService.getForRead(id);
        return streamAttachment(a, fileService);
    }
```

把流式响应组装抽成一个静态方法放在 `AttachmentDTO` 隔壁的工具类里（两个控制器都要用，避免复制粘贴）：

`backend/src/main/java/com/qingyu/care/controller/support/AttachmentResponses.java`

```java
package com.qingyu.care.controller.support;

import com.qingyu.care.entity.ChatAttachment;
import com.qingyu.care.service.FileService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 附件下载响应的组装。两条访问端点（管理端 / 开放 API）鉴权不同但响应形态一致，共用这里。
 *
 * @author ruan
 */
public final class AttachmentResponses {

    private AttachmentResponses() {
    }

    public static ResponseEntity<StreamingResponseBody> stream(ChatAttachment a, FileService fileService) {
        boolean inline = a.getContentType() != null && a.getContentType().startsWith("image/");
        // 文件名可能含中文与特殊字符，用 RFC 5987 编码；同时给一个 ASCII 兜底名
        String encoded = URLEncoder.encode(a.getFileName(), StandardCharsets.UTF_8).replace("+", "%20");
        String disposition = (inline ? "inline" : "attachment") + "; filename*=UTF-8''" + encoded;

        StreamingResponseBody body = out -> {
            try (InputStream in = fileService.openStream(a.getStorageKey())) {
                in.transferTo(out);
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.parseMediaType(a.getContentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate())
                .body(body);
    }
}
```

- [ ] **Step 3: 开放 API 两条端点**

加进 `OpenChatController`（类上已有 `@RequestMapping("/open/v1/chat")`）。鉴权由 `OpenApiAuthInterceptor` 完成，这里只需拿会话。

```java
    /** Widget 用户上传附件 */
    @PostMapping("/files")
    public OpenApiResponse<AttachmentDTO> uploadAttachment(
            @RequestParam("sessionId") String sessionId,
            @RequestParam("file") MultipartFile file) {
        ChatAttachment a = chatAttachmentService.upload(sessionId, file, MessageRole.USER);
        return OpenApiResponse.success(AttachmentDTO.of(a, "/open/v1/chat/files/"));
    }

    @GetMapping("/files/{id}")
    public ResponseEntity<StreamingResponseBody> downloadAttachment(@PathVariable Long id) {
        ChatAttachment a = chatAttachmentService.getForRead(id);
        return AttachmentResponses.stream(a, fileService);
    }
```

⚠️ 实现时按 `OpenChatController` 现有的响应包装惯例来（照抄同文件里其它端点的返回类型写法，不要臆造 `OpenApiResponse`）。

- [ ] **Step 4: 写集成测试**

跨租户访问必须写成 IT——多租户过滤器只在真实 HTTP 请求流经 `TenantInterceptor` 时生效，单测 mock 掉 repository 永远抓不到。

```java
package com.qingyu.care.it;

import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 附件上传与访问的端到端。跨租户访问只有真实 HTTP 请求流经拦截器才拦得住，故必须是 IT。
 *
 * @author ruan
 */
class ChatAttachmentIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    private static byte[] png() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    @Test
    void 上传图片返回带宽高的附件信息() throws Exception {
        // seedApp(...) 播种租户与应用，具体签名照抄本包下其它 IT
        MockMultipartFile file = new MockMultipartFile("file", "截图.png", "image/png", png());

        mockMvc.perform(multipart("/v1/chat/files").file(file).param("sessionId", "it-sess-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.width").value(10))
                .andExpect(jsonPath("$.data.height").value(10))
                .andExpect(jsonPath("$.data.contentType").value("image/png"))
                .andExpect(jsonPath("$.data.url").value(org.hamcrest.Matchers.startsWith("/v1/chat/files/")));
    }

    @Test
    void 可执行文件改名成png被拒且不落库() throws Exception {
        MockMultipartFile fake = new MockMultipartFile(
                "file", "photo.png", "image/png", new byte[]{0x4D, 0x5A, (byte) 0x90, 0x00});

        mockMvc.perform(multipart("/v1/chat/files").file(fake).param("sessionId", "it-sess-1"))
                .andExpect(status().isOk())   // 本项目 BusinessException 走 HTTP 200 + code 400
                .andExpect(jsonPath("$.code").value(400));
    }
}
```

⚠️ 实现时先读 `backend/src/test/java/com/qingyu/care/it/` 下的既有 IT，照抄它们的 `seedApp(...)` 播种方式与鉴权头写法。本项目 `BusinessException` 的响应形态是 **HTTP 200 + body 里 `code=400`**（见 `GlobalExceptionHandler`），断言要按实际来。

- [ ] **Step 5: 跑测试**

Run: 先确认 `netstat -ano | grep ":8080" | grep LISTENING` 无输出，再 `cd backend && mvn verify -Dit.test=ChatAttachmentIT`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/dto/AttachmentDTO.java backend/src/main/java/com/qingyu/care/controller/support/AttachmentResponses.java backend/src/main/java/com/qingyu/care/controller/ChatController.java backend/src/main/java/com/qingyu/care/controller/open/OpenChatController.java backend/src/test/java/com/qingyu/care/it/ChatAttachmentIT.java
git commit -m "feat(backend): 附件上传与访问端点

桶是私有的，客户端拿不到裸 S3 地址，一律经代理端点。
图片 inline 供预览，其余强制 attachment——不给浏览器渲染的机会。
url 按调用来源生成不存库：管理端与 Widget 的鉴权路径不同。"
```

---

### Task 6: 消息关联、历史消息 DTO 与实时推送

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/dto/ChatMessageDTO.java`
- Modify: `backend/src/main/java/com/qingyu/care/dto/ChatRequest.java`
- Modify: `backend/src/main/java/com/qingyu/care/dto/open/OpenChatRequest.java`
- Modify: `backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java`
- Modify: `backend/src/main/java/com/qingyu/care/controller/ChatController.java`（`getSessionMessages` 改返回 DTO）
- Modify: `backend/src/main/java/com/qingyu/care/service/realtime/RealtimeNotifier.java`
- Test: `backend/src/test/java/com/qingyu/care/service/media/MessageAttachmentWiringTest.java`

**Interfaces:**
- Consumes: `ChatAttachmentService.attachToMessage(List<Long>, String, Long)`、`ChatAttachmentRepository.findByMessageIdIn(...)`、`AttachmentDTO.of(...)`
- Produces: `ChatMessageDTO`（含 `attachments`）；`ChatRequest.attachmentIds` / `OpenChatRequest.attachmentIds`

- [ ] **Step 1: 请求 DTO 加字段**

`ChatRequest` 与 `OpenChatRequest` 各加：

```java
    /** 本条消息携带的附件 id（先经 /files 上传拿到）。可空 */
    private List<Long> attachmentIds;
```

- [ ] **Step 2: 历史消息 DTO**

现在 `ChatController.getSessionMessages` 直接返回 `List<ChatMessage>` **实体**，附件挂不上去——不改的话刷新后历史消息里的图会全没。

```java
package com.qingyu.care.dto;

import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.enums.MessageRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 历史消息出参。原先直接返回 ChatMessage 实体，附件无处挂载；
 * 走 DTO 后附件由 Service 层批量查出来组装（避免逐条 LAZY 的 N+1）。
 *
 * @author ruan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageDTO {

    private Long id;
    private MessageRole role;
    private String content;
    private String uiSpec;
    private Integer feedback;
    private Boolean fallback;
    private LocalDateTime createdAt;
    private List<AttachmentDTO> attachments;

    public static ChatMessageDTO of(ChatMessage m, List<AttachmentDTO> attachments) {
        return ChatMessageDTO.builder()
                .id(m.getId())
                .role(m.getRole())
                .content(m.getContent())
                .uiSpec(m.getUiSpec())
                .feedback(m.getFeedback())
                .fallback(m.getFallback())
                .createdAt(m.getCreatedAt())
                .attachments(attachments)
                .build();
    }
}
```

`ChatController.getSessionMessages` 改成组装它：一次 `findByMessageIdIn(消息 id 列表)` 拿全部附件，按 `messageId` 分组后塞进各条消息，**不要在循环里逐条查**。

- [ ] **Step 3: 发消息时回填 messageId**

在 `ChatServiceImpl` 保存用户消息之后调用：

```java
chatAttachmentService.attachToMessage(request.getAttachmentIds(), sessionId, savedUserMessage.getId());
```

⚠️ 落点是**用户消息**保存之后（附件是用户/坐席发的，不是 AI 生成的）。`agentReply` 那条路径同理，用坐席消息的 id。

- [ ] **Step 4: RealtimeNotifier 补字段**

`messageJson()` 现在只序列化 `{sessionId, messageId, role, content, createdAt}` 五个扁平字段。不补的话**用户发的图推不到坐席端，双向就断了一半**。加 `attachments`（`List<AttachmentDTO>`，URL 前缀用管理端的 `/v1/chat/files/`，因为消费方是坐席工作台）与 `uiSpec`。

- [ ] **Step 5: 写接线测试**

这个测试守护的是「附件真的被挂到了消息上」这条接线——删掉调用后必须转红。

装配直接照搬同构的 `ChatCardWiringTest`（它守护的是卡片接线，本用例守护附件接线，形态一致）。文件放 `backend/src/test/java/com/qingyu/care/service/impl/ChatAttachmentWiringTest.java`。

```java
package com.qingyu.care.service.impl;

import tools.jackson.databind.ObjectMapper;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.ChatRequest;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.service.*;
import com.qingyu.care.service.media.ChatAttachmentService;
import com.qingyu.care.service.realtime.RealtimeNotifier;
import com.qingyu.care.support.SpringAiResponses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 守护「发消息时附件真的被关联到【用户消息】上」这条接线。
 * 这类接线最容易在重构中被静默删掉——删了功能照常跑，只是附件永远停留为孤儿、
 * 24 小时后被清理任务连同文件一起删掉，用户看到的是「图发出去了，过一天没了」。
 *
 * <p>装配照搬 ChatCardWiringTest（同类接线用例）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatAttachmentWiringTest {

    @Mock VectorStore vectorStore;
    @Mock ChatSessionRepository sessionRepository;
    @Mock ChatMessageRepository messageRepository;
    @Mock PromptTemplateService promptTemplateService;
    @Mock HandoverRuleService handoverRuleService;
    @Mock ModelRouterService modelRouterService;
    @Mock ChatStrategyService chatStrategyService;
    @Mock HybridRetrievalService hybridRetrievalService;
    @Mock RetrievalScopeResolver retrievalScopeResolver;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock TransactionTemplate transactionTemplate;
    @Mock AbTestService abTestService;
    @Mock ConfigService configService;
    @Mock ChatToolService chatToolService;
    @Mock RealtimeNotifier realtimeNotifier;
    @Mock EmbeddingModelProvider embeddingModelProvider;
    @Mock ChatAttachmentService chatAttachmentService;
    @Spy UiSpecService uiSpecService = new UiSpecService(new ObjectMapper());
    @Spy ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks ChatServiceImpl chatService;

    @BeforeEach
    void seedTenantContext() {
        TenantContext.setTenantId(1L);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void 发消息时用用户消息的id关联附件() {
        ChatServiceImpl selfMock = mock(ChatServiceImpl.class);
        when(selfMock.searchKnowledge(anyString(), any())).thenReturn(List.of());
        chatService.setSelf(selfMock);

        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));
        // 给保存的消息按顺序发 id：用户消息拿 1，AI 消息拿 2
        AtomicLong seq = new AtomicLong();
        when(messageRepository.save(any())).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            m.setId(seq.incrementAndGet());
            return m;
        });
        when(abTestService.resolveVariantStrategy(null, null)).thenReturn(Optional.empty());
        when(chatStrategyService.getActiveStrategy("default")).thenReturn(
                ChatStrategy.builder().name("默认").scenario("default")
                        .allowNoKnowledge(true).fallbackMessage("请稍等").build());
        when(chatToolService.resolve()).thenReturn(ChatToolService.ToolBundle.empty());
        when(promptTemplateService.getActiveTemplateContent()).thenReturn("你是轻语客服 {context}");
        when(messageRepository.findTop20BySessionIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(configService.get(any(), any(), any(), any())).thenReturn("default");
        when(modelRouterService.callWithFallback(eq("default"), any()))
                .thenReturn(SpringAiResponses.of("收到您的截图", 10));

        chatService.chat(ChatRequest.builder()
                .sessionId("s-1")
                .message("这是报错截图")
                .attachmentIds(List.of(7L))
                .build());

        // 第三个参数必须是【用户消息】的 id（1），不是 AI 消息的 id（2）——
        // 挂错了会让附件显示在 AI 的气泡里
        verify(chatAttachmentService).attachToMessage(eq(List.of(7L)), eq("s-1"), eq(1L));
    }

    @Test
    void 没带附件时不调用关联() {
        ChatServiceImpl selfMock = mock(ChatServiceImpl.class);
        when(selfMock.searchKnowledge(anyString(), any())).thenReturn(List.of());
        chatService.setSelf(selfMock);

        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(abTestService.resolveVariantStrategy(null, null)).thenReturn(Optional.empty());
        when(chatStrategyService.getActiveStrategy("default")).thenReturn(
                ChatStrategy.builder().name("默认").scenario("default")
                        .allowNoKnowledge(true).fallbackMessage("请稍等").build());
        when(chatToolService.resolve()).thenReturn(ChatToolService.ToolBundle.empty());
        when(promptTemplateService.getActiveTemplateContent()).thenReturn("你是轻语客服 {context}");
        when(messageRepository.findTop20BySessionIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(configService.get(any(), any(), any(), any())).thenReturn("default");
        when(modelRouterService.callWithFallback(eq("default"), any()))
                .thenReturn(SpringAiResponses.of("你好", 10));

        chatService.chat(ChatRequest.builder().sessionId("s-1").message("你好").build());

        verify(chatAttachmentService, never()).attachToMessage(any(), any(), any());
    }
}
```

⚠️ 两点必须先核对再动手：`ChatServiceImpl` 的构造/注入形态可能与上面列的 `@Mock` 集合有出入（**以 `ChatCardWiringTest` 当前实际内容为准**，它是活的参照）；`SpringAiResponses.of(...)` 是既有测试辅助类，直接复用。

- [ ] **Step 6: 跑全部单测**

Run: `cd backend && mvn test`
Expected: 全绿

- [ ] **Step 7: 变异自证**

把 Step 3 那行 `attachToMessage(...)` 删掉，重跑：`发消息时用用户消息的id关联附件` 必须转红。确认后还原并在报告里写明。

- [ ] **Step 8: 提交**

```bash
git add -A backend/src/main/java/com/qingyu/care/dto backend/src/main/java/com/qingyu/care/service backend/src/main/java/com/qingyu/care/controller backend/src/test/java/com/qingyu/care/service/media
git commit -m "feat(backend): 消息关联附件、历史消息走 DTO、实时推送补附件

getSessionMessages 原先直接返回实体，附件无处挂载——不改的话刷新后
历史消息里的图会全没。RealtimeNotifier 那五个扁平字段同理，不补附件
就推不到坐席端，双向断一半。"
```

---

### Task 7: 前端两页（ChatPage 附件 + AgentPage 接入卡片与附件）

**Files:**
- Create: `frontend/src/components/chat/Attachments.tsx`
- Modify: `frontend/src/pages/ChatPage.tsx`
- Modify: `frontend/src/pages/AgentPage.tsx`
- Modify: `frontend/src/api/chat.ts`、`frontend/src/types/index.ts`

**Interfaces:**
- Consumes: `POST /v1/chat/files`、`GET /v1/chat/files/{id}`、消息里的 `attachments`
- Produces: `<Attachments items={...} />`

- [ ] **Step 1: 类型与 API**

`types/index.ts` 加：

```ts
export interface Attachment {
  id: number;
  fileName: string;
  contentType: string;
  fileSize: number;
  width?: number | null;
  height?: number | null;
  url: string;
}
```

并给消息类型加 `attachments?: Attachment[]`。

`api/chat.ts` 加：

```ts
export const uploadChatFile = async (sessionId: string, file: File): Promise<Attachment> => {
  const form = new FormData();
  form.append('sessionId', sessionId);
  form.append('file', file);
  // 不要手工设置 Content-Type，浏览器需要自己带 boundary
  return client.post('/v1/chat/files', form);
};
```

- [ ] **Step 2: 附件渲染组件**

⚠️ **不要复用卡片的 `image` 组件**。`safeUrl` 的白名单只放行 `http:`/`https:`，而附件 URL 是相对路径 `/v1/chat/files/1`——`new URL('/v1/chat/files/1')` 会抛异常走 catch 返回空串，图片**静默不显示且无任何报错**。而且语义不同：卡片 image 的 URL 是 LLM 生成的不可信输入才需要白名单，附件 URL 是我们自己生成的。

```tsx
import type { Attachment } from '@/types';

/** 字节数转人类可读 */
function humanSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

/**
 * 消息附件渲染。图片内嵌预览（带宽高预留占位避免加载时布局跳动），
 * 其余显示为可下载的文件条。
 *
 * 文件名是用户输入，靠 React 默认转义防 XSS——不要改用 dangerouslySetInnerHTML。
 */
export function Attachments({ items }: { items?: Attachment[] }) {
  if (!items || items.length === 0) return null;

  return (
    <div className="flex flex-col gap-2 mt-2">
      {items.map((a) =>
        a.contentType.startsWith('image/') ? (
          <a key={a.id} href={a.url} target="_blank" rel="noopener noreferrer">
            <img
              src={a.url}
              alt={a.fileName}
              width={a.width ?? undefined}
              height={a.height ?? undefined}
              className="max-w-xs max-h-64 rounded border object-contain"
            />
          </a>
        ) : (
          <a
            key={a.id}
            href={a.url}
            download
            className="flex items-center gap-2 px-3 py-2 rounded border text-xs hover:bg-muted"
          >
            <span aria-hidden>📎</span>
            <span className="truncate max-w-[16rem]">{a.fileName}</span>
            <span className="text-muted-foreground shrink-0">{humanSize(a.fileSize)}</span>
          </a>
        )
      )}
    </div>
  );
}
```

- [ ] **Step 3: ChatPage 接入**

在消息渲染处（现有的「有 `uiParts` 走 `JsonRender`，否则渲染 `content`」分支之后）平行加 `<Attachments items={msg.attachments} />`。

输入区加附件按钮：`<input type="file" hidden>` + 一个触发它的按钮；选中后立即调 `uploadChatFile`，把返回的 `Attachment` 存进 `pendingAttachments` 状态并显示缩略预览（可移除）；发送时把 `pendingAttachments.map(a => a.id)` 作为 `attachmentIds` 一起提交，发送成功后清空。

- [ ] **Step 4: AgentPage 接入卡片渲染与附件**

`AgentPage` 现在是裸 `{msg.content}`（约 `:259`），连卡片都不渲染。改成与 `ChatPage` 同款三段式：

```tsx
{msg.uiParts?.length ? (
  <CardErrorBoundary><JsonRender parts={msg.uiParts} onAction={...} /></CardErrorBoundary>
) : (
  <span>{msg.content}</span>
)}
<Attachments items={msg.attachments} />
```

⚠️ 照抄 `ChatPage` 现有的 `JsonRender` + `CardErrorBoundary` 用法（**必须带错误边界**——React 19 下畸形卡片结构会卸载整棵树导致白屏，本仓库有成文教训）。坐席侧同样加上传控件，走 `POST /v1/chat/files`。

- [ ] **Step 5: 构建**

Run: `cd frontend && pnpm build && pnpm lint`
Expected: build 通过；lint 的既有 error 数与改动前一致（本仓库有 69 个既有基线 error，**不要新增**）

- [ ] **Step 6: 提交**

```bash
git add frontend/src
git commit -m "feat(frontend): 消息附件渲染与上传；坐席台补上卡片渲染

附件不复用卡片 image 组件：safeUrl 只放行 http/https，相对路径会被
fail-closed 拒掉且静默不显示；且附件 URL 是我们自己生成的，本就不需要
那道针对 LLM 输出的白名单。"
```

---

### Task 8: Widget 上传与渲染

**Files:**
- Create: `widget/src/attachments.ts`
- Modify: `widget/src/ui.ts`、`widget/src/api.ts`
- Modify: `backend/src/main/resources/static/widget.js`（构建产物，必须提交）
- Test: `widget/src/__tests__/attachments.test.ts`

**Interfaces:**
- Consumes: `POST /open/v1/chat/files`、消息里的 `attachments`
- Produces: `renderAttachments(items) : HTMLElement | null`

- [ ] **Step 1: 写失败测试**

widget 跑在客户网页里，XSS 风险最高。这条测试守护「文件名不被当 HTML 执行」。

```ts
import { describe, it, expect } from 'vitest';
import { renderAttachments } from '../attachments';

describe('renderAttachments', () => {
  it('文件名里的 HTML 不被执行', () => {
    const el = renderAttachments([
      { id: 1, fileName: '<img onerror=alert(1)>.png', contentType: 'application/pdf',
        fileSize: 100, url: '/open/v1/chat/files/1' },
    ]);
    // 文件名原样作为文本出现，且没有真的创建出 img 元素
    expect(el!.textContent).toContain('<img onerror=alert(1)>.png');
    expect(el!.querySelectorAll('img').length).toBe(0);
  });

  it('图片带宽高属性用于预留占位', () => {
    const el = renderAttachments([
      { id: 2, fileName: 'a.png', contentType: 'image/png',
        fileSize: 100, width: 800, height: 600, url: '/open/v1/chat/files/2' },
    ]);
    const img = el!.querySelector('img')!;
    expect(img.getAttribute('width')).toBe('800');
    expect(img.getAttribute('height')).toBe('600');
  });

  it('空列表返回 null', () => {
    expect(renderAttachments([])).toBeNull();
    expect(renderAttachments(undefined)).toBeNull();
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd widget && pnpm test`
Expected: FAIL，模块不存在

- [ ] **Step 3: 写实现**

⚠️ 全程 `textContent`，**任何地方都不用 `innerHTML`**——这是本仓库 widget 侧的铁律。

```ts
/** 消息附件的纯 DOM 渲染。全部走 textContent，不用 innerHTML。 */

export interface WidgetAttachment {
  id: number;
  fileName: string;
  contentType: string;
  fileSize: number;
  width?: number | null;
  height?: number | null;
  url: string;
}

function humanSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

export function renderAttachments(items?: WidgetAttachment[]): HTMLElement | null {
  if (!items || items.length === 0) return null;

  const box = document.createElement('div');
  box.className = 'qy-attachments';

  for (const a of items) {
    if (a.contentType.startsWith('image/')) {
      const link = document.createElement('a');
      link.href = a.url;
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
      const img = document.createElement('img');
      img.src = a.url;
      img.alt = a.fileName;                       // alt 走属性赋值，不拼 HTML
      if (a.width) img.setAttribute('width', String(a.width));
      if (a.height) img.setAttribute('height', String(a.height));
      img.className = 'qy-attach-img';
      link.appendChild(img);
      box.appendChild(link);
    } else {
      const link = document.createElement('a');
      link.href = a.url;
      link.setAttribute('download', '');
      link.className = 'qy-attach-file';
      const name = document.createElement('span');
      name.textContent = a.fileName;              // 用户输入，必须 textContent
      const size = document.createElement('span');
      size.className = 'qy-attach-size';
      size.textContent = humanSize(a.fileSize);
      link.appendChild(name);
      link.appendChild(size);
      box.appendChild(link);
    }
  }
  return box;
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd widget && pnpm test`
Expected: PASS

- [ ] **Step 5: 接进 UI 与 API**

`api.ts` 加上传函数（`POST /open/v1/chat/files`，`FormData`，**不手工设 Content-Type**）。
`ui.ts`：消息渲染处调 `renderAttachments` 追加；输入区加一个附件按钮（隐藏的 `<input type="file">`），选中后上传、显示待发送预览，发送时带 `attachmentIds`。
样式加进既有的 Shadow DOM 样式表。⚠️ 类名用 `qy-attach-*` 前缀，**先 grep 确认不与现有类名冲突**——本仓库踩过 `qy-input` 撞 composer 底栏的坑。

- [ ] **Step 6: 变异自证**

把 `name.textContent = a.fileName` 改成 `name.innerHTML = a.fileName`，重跑：`文件名里的 HTML 不被执行` 必须转红。确认后还原并在报告里写明。

- [ ] **Step 7: 重新构建 widget.js 并提交**

Run: `cd widget && pnpm build`

⚠️ `widget.js` 是**提交进仓库的构建产物**，由后端作为静态资源对外提供。改了 `widget/src` 必须重新 build 并把产物一起提交。

```bash
git add widget/src backend/src/main/resources/static/widget.js
git commit -m "feat(widget): 附件渲染与上传

widget 跑在客户网页里，文件名全程 textContent——一个叫
<img onerror=alert(1)>.png 的文件用 innerHTML 就中招。"
```

---

### Task 9: 全量验证、真机冒烟与文档

**Files:**
- Modify: `CLAUDE.md`
- Modify: `SaaS平台规划.md`

- [ ] **Step 1: 全量验证**

```bash
# 先确认没有 dev 后端占着 8080，否则 WebhookDeliveryIT 必红（与本特性无关）
netstat -ano | grep ":8080" | grep LISTENING
cd backend && mvn verify
cd ../frontend && pnpm build
cd ../widget && pnpm test
```
Expected: 全绿

- [ ] **Step 2: 真机冒烟（六项，自动化覆盖不到）**

启动 `docker compose up -d`、后端、前端，逐项验证并记录结果：

1. **双向各验一次**：Widget 发图 → 坐席工作台看得到；坐席回图 → 用户端看得到。
2. **图片不跳动**：发一张大图，观察加载过程中下方内容**没有**位移（验证 `width`/`height` 真的生效）。
3. **改名文件被拒**：把任意 `.exe` 改名成 `.jpg` 上传 → 提示「文件内容与扩展名不符」且**库里没有新行**。
4. **文件名 XSS**：上传一个名为 `<img onerror=alert(1)>.pdf` 的文件 → 三端都把它当文本显示，不弹窗、不产生 img 元素。
5. **孤儿清理**：上传但不发送 → 把 `qingyu.care.attachment.orphan-retain-hours` 临时改成 0、`cleanup-interval-ms` 改成 30000 重启 → 确认库行没了**且 S3 对象也没了**（用 `docker exec` 进 RustFS 或看控制台 `:9001` 确认，只删库行会留孤儿对象）。
6. **非图片强制下载**：点一个 PDF 附件 → 触发下载而非浏览器内渲染（看响应头 `Content-Disposition: attachment`）。

- [ ] **Step 3: 写 CLAUDE.md**

在「架构要点」下新增「聊天附件（多媒体消息）」一节，写清：
- 上传与发消息分两步、`messageId` 可空的两个后果（孤儿清理 + IDOR 校验）
- 桶私有 → 一律经代理端点，图片 `inline`、其余 `attachment`
- ⚠️ **附件不能复用卡片的 `image` 组件**（`safeUrl` 拒相对路径且 fail-closed 静默）
- ⚠️ **不要改 `base-path: knowledge/`**（存量文件 URL 已落库）
- ⚠️ Tika 是传递引入的，不要新增显式依赖
- 三端渲染文件名一律转义

并在「改动 X 后必须手工验证」系列里加一节「改动附件链路后必须手工验证」，列出上面冒烟的 3/4/5 三项（这三条是自动化最容易漏、后果最重的）。

- [ ] **Step 4: 路线图标记完成**

`SaaS平台规划.md` 里把「多媒体消息（图片 / 文件）」勾成 `[x]` 并补一行落地说明（照抄前两项的写法）。Phase 5.1 三项至此全部完成。

- [ ] **Step 5: 提交**

```bash
git add CLAUDE.md SaaS平台规划.md
git commit -m "docs: 多媒体消息收官——CLAUDE.md 架构要点与手工验证项、路线图 Phase 5.1 收尾"
```

---

## 自检结果

**Spec 覆盖**：§3 数据模型 → Task 1；§4.2 校验四道 → Task 2；§4.1/§4.3 上传与访问 → Task 3+5；§3.1 孤儿与 IDOR → Task 4；§5.1/§5.2/§5.3 渲染 → Task 7+8；§5.4 RealtimeNotifier → Task 6；§6 安全边界 → 分散在 2/4/5/7/8；§7 测试 → 各任务内；§8 冒烟 → Task 9。**无遗漏**。

**类型一致性**：`ValidatedFile(contentType, image, width, height)` 在 Task 2 定义、Task 4 消费；`uploadChatBytes(byte[], String, String) : String` 在 Task 3 定义、Task 4 消费；`AttachmentDTO.of(ChatAttachment, String)` 在 Task 5 定义、Task 6 消费；`attachToMessage(List<Long>, String, Long)` 在 Task 4 定义、Task 6 消费。一致。

**已知的两处「实现时须以实际为准」**（计划里已标注，实现者必须先读既有代码再动手）：
1. `OpenChatController` 的响应包装类型——照抄同文件既有端点，不要臆造。
2. IT 的 `seedApp(...)` 播种方式与鉴权头——照抄 `it/` 包下既有 IT。
