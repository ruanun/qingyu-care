# 流式聊天完善 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把管理端 `ChatPage` 与可嵌入 widget 的主聊天改成 SSE 流式增量渲染，并让流式路径与非流式 `chat()` 达到完全同语义（含结构化卡片增量下发）。

**Architecture:** 后端 `streamChat` 从返回裸 `Flux<String>` 改为返回 `Flux<ChatStreamEvent>`（`START/DELTA/CARD/DONE/ERROR` 五类事件），控制器只负责映射成 `ServerSentEvent`。` ```spec ` 围栏的增量解析在后端完成（`StreamingSpecSplitter`），客户端零解析器、直接复用 SP1/SP2 已有的渲染器。落库仍走 `UiSpecService.parse(完整原文)`，与非流式共用同一解析实现。

**Tech Stack:** Spring Boot 4.1 / Spring AI 2.0 / Reactor / JDK 17 / Jackson 3（`tools.jackson.databind`）/ React 19 + Vite / widget 纯 TS + Shadow DOM / JUnit5 + Mockito + Testcontainers / vitest

设计依据：`docs/superpowers/specs/2026-08-04-streaming-chat-design.md`

## Global Constraints

- 全部使用简体中文：对话、代码注释、commit 信息；新建文件的 javadoc 作者写 `@author ruan`。
- JDK 17；后端新增单测优先写成纯 Mockito 单测（Surefire，免 Docker）；`*IT.java` 放 `it/` 包、继承 `support/AbstractPostgresIT`（Failsafe，需 Docker Desktop）。
- 允许 `git commit`（已在特性分支 `feature/streaming-chat` 上）；**未获授权不 `git push`**。
- 前端/widget 包管理器一律 `pnpm`。
- `widget/` 改动后必须 `pnpm build` 重新产出 `backend/src/main/resources/static/widget.js` 并提交（`.gitattributes` 强制 LF）。
- 前端仓库**没有 vitest**，前端改动靠 `pnpm build`（tsc -b）+ `pnpm lint` + 真机冒烟验证；不要为此引入 vitest（范围外）。
- fail-soft 铁律：卡片解析、序列化、落库失败一律只告警降级，绝不抛异常阻断对话。
- `error` 事件的 message 必须是固定文案，**不得回传原始异常信息**（widget 运行在客户网页里）；异常详情只进日志。
- token 计量保持「开放 API 专属」：只有 `OpenChatController` 传入 `onTokensUsed` 回调。

---

### Task 1: 事件模型 `ChatStreamEvent` + SSE 映射器

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/dto/ChatStreamEvent.java`
- Create: `backend/src/main/java/com/qingyu/care/service/ChatStreamSseMapper.java`
- Test: `backend/src/test/java/com/qingyu/care/service/ChatStreamSseMapperTest.java`

**Interfaces:**
- Consumes: 无（首个任务）
- Produces: `ChatStreamEvent`（含静态工厂 `start/delta/card/done/error`、枚举 `ChatStreamEvent.Type`、payload record `Start/Delta/Card/Done/Error`）；`ChatStreamSseMapper.toSse(ChatStreamEvent) -> ServerSentEvent<String>`。后续 Task 2/4/5/6/7 全部依赖这两个类型。

- [ ] **Step 1: 写失败的测试**

`backend/src/test/java/com/qingyu/care/service/ChatStreamSseMapperTest.java`：

```java
package com.qingyu.care.service;

import com.qingyu.care.dto.ChatStreamEvent;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单元测试：ChatStreamEvent → ServerSentEvent 的事件名与 payload 序列化契约。
 * 协议见 docs/superpowers/specs/2026-08-04-streaming-chat-design.md §3。
 *
 * @author ruan
 */
class ChatStreamSseMapperTest {

    private final ChatStreamSseMapper mapper = new ChatStreamSseMapper(new ObjectMapper());

    @Test
    void delta事件名小写且payload为JSON对象() {
        ServerSentEvent<String> sse = mapper.toSse(ChatStreamEvent.delta("你好"));

        assertThat(sse.event()).isEqualTo("delta");
        assertThat(sse.data()).isEqualTo("{\"text\":\"你好\"}");
    }

    @Test
    void delta中的换行被JSON转义为单行() {
        // 关键性质：delta 用 JSON 而非裸文本，正是为了让含换行的 chunk 在 SSE 层永远单行
        ServerSentEvent<String> sse = mapper.toSse(ChatStreamEvent.delta("第一行\n第二行"));

        assertThat(sse.data()).doesNotContain("\n");
        assertThat(sse.data()).isEqualTo("{\"text\":\"第一行\\n第二行\"}");
    }

    @Test
    void start携带会话与转人工建议与知识命中() {
        ServerSentEvent<String> sse = mapper.toSse(
                ChatStreamEvent.start("s_abc", true, List.of()));

        assertThat(sse.event()).isEqualTo("start");
        assertThat(sse.data()).contains("\"sessionId\":\"s_abc\"")
                .contains("\"suggestHandover\":true")
                .contains("\"knowledgeHits\":[]");
    }

    @Test
    void done的messageId为空时序列化为null() {
        ServerSentEvent<String> sse = mapper.toSse(ChatStreamEvent.done(null));

        assertThat(sse.event()).isEqualTo("done");
        assertThat(sse.data()).isEqualTo("{\"messageId\":null}");
    }

    @Test
    void 序列化失败时降级为空对象不抛异常() {
        // fail-soft：payload 里塞一个无法序列化的对象，不能让整条流因序列化崩掉
        Object bad = new Object() {
            @SuppressWarnings("unused")
            public Object getSelf() { throw new IllegalStateException("boom"); }
        };
        ServerSentEvent<String> sse = mapper.toSse(ChatStreamEvent.card(bad));

        assertThat(sse.event()).isEqualTo("card");
        assertThat(sse.data()).isEqualTo("{}");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn test -Dtest=ChatStreamSseMapperTest`
Expected: 编译失败，`ChatStreamEvent` / `ChatStreamSseMapper` 不存在。

- [ ] **Step 3: 实现事件模型**

`backend/src/main/java/com/qingyu/care/dto/ChatStreamEvent.java`：

```java
package com.qingyu.care.dto;

import java.util.List;

/**
 * 流式对话的一个 SSE 事件。协议见 docs/superpowers/specs/2026-08-04-streaming-chat-design.md §3。
 *
 * <p>事件语义：
 * <ul>
 *   <li>{@code START} —— 检索完成、LLM 开口前发出，携带 sessionId / 转人工建议 / 知识命中；
 *       客户端据此建立会话上下文（widget 靠它提前建坐席通道）。</li>
 *   <li>{@code DELTA} —— 文本增量。payload 是 JSON 对象而非裸文本，让含换行的 chunk
 *       在 SSE 层永远是单行，免去三处独立实现踩同一套多行 data 边界。</li>
 *   <li>{@code CARD} —— 一个 ```spec 围栏闭合后的完整 UI 树。</li>
 *   <li>{@code DONE} —— 落库完成，携带 AI 消息 ID（未落库时为 null）。</li>
 *   <li>{@code ERROR} —— 固定文案，绝不含原始异常信息。</li>
 * </ul>
 *
 * @author ruan
 */
public record ChatStreamEvent(Type type, Object payload) {

    public enum Type { START, DELTA, CARD, DONE, ERROR }

    public record Start(String sessionId, boolean suggestHandover,
                        List<ChatResponse.KnowledgeHit> knowledgeHits) {}

    public record Delta(String text) {}

    public record Card(Object node) {}

    public record Done(Long messageId) {}

    public record Error(String message) {}

    public static ChatStreamEvent start(String sessionId, boolean suggestHandover,
                                        List<ChatResponse.KnowledgeHit> knowledgeHits) {
        return new ChatStreamEvent(Type.START, new Start(sessionId, suggestHandover, knowledgeHits));
    }

    public static ChatStreamEvent delta(String text) {
        return new ChatStreamEvent(Type.DELTA, new Delta(text));
    }

    public static ChatStreamEvent card(Object node) {
        return new ChatStreamEvent(Type.CARD, new Card(node));
    }

    public static ChatStreamEvent done(Long messageId) {
        return new ChatStreamEvent(Type.DONE, new Done(messageId));
    }

    public static ChatStreamEvent error(String message) {
        return new ChatStreamEvent(Type.ERROR, new Error(message));
    }
}
```

`backend/src/main/java/com/qingyu/care/service/ChatStreamSseMapper.java`：

```java
package com.qingyu.care.service;

import com.qingyu.care.dto.ChatStreamEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * ChatStreamEvent → SSE 行的唯一映射点（管理端与开放端两个控制器共用，保证两端协议一致）。
 * 事件名取枚举小写；payload 一律序列化为 JSON 对象。
 * fail-soft：序列化失败降级为空对象 {}，绝不让一个畸形 payload 打断整条流。
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatStreamSseMapper {

    private final ObjectMapper objectMapper;

    public ServerSentEvent<String> toSse(ChatStreamEvent event) {
        return ServerSentEvent.<String>builder(writeJson(event.payload()))
                .event(event.type().name().toLowerCase())
                .build();
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("流式事件序列化失败，降级为空对象: {}", e.getMessage());
            return "{}";
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn test -Dtest=ChatStreamSseMapperTest`
Expected: 5 个测试全绿。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/dto/ChatStreamEvent.java \
        backend/src/main/java/com/qingyu/care/service/ChatStreamSseMapper.java \
        backend/src/test/java/com/qingyu/care/service/ChatStreamSseMapperTest.java
git commit -m "feat(backend): 流式对话事件模型 ChatStreamEvent + SSE 映射器"
```

---

### Task 2: 增量围栏切分器 `StreamingSpecSplitter`

**Files:**
- Create: `backend/src/main/java/com/qingyu/care/service/StreamingSpecSplitter.java`
- Test: `backend/src/test/java/com/qingyu/care/service/StreamingSpecSplitterTest.java`

**Interfaces:**
- Consumes: `ChatStreamEvent`（Task 1）
- Produces: `new StreamingSpecSplitter(ObjectMapper)`；`List<ChatStreamEvent> accept(String chunk)`；`List<ChatStreamEvent> flush()`。Task 4 在流管道里使用。

**关键约束：识别规则必须与 `UiSpecService.FENCE`（`` ```spec\s*\r?\n(.*?)``` ``）同源。**两者一旦漂移，会出现「流式渲染成卡片、落库却是文本」的分裂。

- [ ] **Step 1: 写失败的测试**

`backend/src/test/java/com/qingyu/care/service/StreamingSpecSplitterTest.java`：

```java
package com.qingyu.care.service;

import com.qingyu.care.dto.ChatStreamEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单元测试：流式 ```spec 围栏增量切分。无 Spring 容器依赖，可直接 new。
 *
 * 核心不变量：无论 chunk 怎么切，产出的文本拼接结果都与 UiSpecService.parse(全文) 一致，
 * 卡片节点也一一对应——落库走 parse、渲染走 splitter，两者不可漂移。
 *
 * @author ruan
 */
class StreamingSpecSplitterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 把一串 chunk 喂进去，收集全部事件（含 flush）。 */
    private List<ChatStreamEvent> feed(List<String> chunks) {
        StreamingSpecSplitter splitter = new StreamingSpecSplitter(objectMapper);
        List<ChatStreamEvent> events = new ArrayList<>();
        for (String c : chunks) {
            events.addAll(splitter.accept(c));
        }
        events.addAll(splitter.flush());
        return events;
    }

    /** 把全文按单字符切开喂入——最严苛的切割方式。 */
    private List<ChatStreamEvent> feedCharByChar(String full) {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < full.length(); i++) {
            chunks.add(String.valueOf(full.charAt(i)));
        }
        return feed(chunks);
    }

    private String textOf(List<ChatStreamEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (ChatStreamEvent e : events) {
            if (e.type() == ChatStreamEvent.Type.DELTA) {
                sb.append(((ChatStreamEvent.Delta) e.payload()).text());
            }
        }
        return sb.toString();
    }

    private long cardCount(List<ChatStreamEvent> events) {
        return events.stream().filter(e -> e.type() == ChatStreamEvent.Type.CARD).count();
    }

    @Test
    void 无围栏时原样吐文本() {
        List<ChatStreamEvent> events = feed(List.of("你好", "，世界"));

        assertThat(textOf(events)).isEqualTo("你好，世界");
        assertThat(cardCount(events)).isZero();
    }

    @Test
    void 纯空白chunk也要吐出不能被丢弃() {
        // 空白在流式里是有意义的（词间空格），不能照搬 UiSpecService 的 isBlank 过滤
        List<ChatStreamEvent> events = feed(List.of("hello", " ", "world"));

        assertThat(textOf(events)).isEqualTo("hello world");
    }

    @Test
    void 完整围栏产出卡片且围栏标记不进文本() {
        String full = "请看：\n```spec\n{\"type\":\"card\"}\n```\n还有别的吗";

        List<ChatStreamEvent> events = feed(List.of(full));

        assertThat(cardCount(events)).isEqualTo(1);
        assertThat(textOf(events)).doesNotContain("```").doesNotContain("spec");
        assertThat(textOf(events)).contains("请看：").contains("还有别的吗");
    }

    @Test
    void 逐字符喂入与整块喂入结果一致() {
        String full = "请看：\n```spec\n{\"type\":\"card\",\"props\":{\"title\":\"订单\"}}\n```\n还有别的吗";

        List<ChatStreamEvent> whole = feed(List.of(full));
        List<ChatStreamEvent> byChar = feedCharByChar(full);

        assertThat(textOf(byChar)).isEqualTo(textOf(whole));
        assertThat(cardCount(byChar)).isEqualTo(cardCount(whole));
    }

    @Test
    void 起始标记被chunk切断仍能识别() {
        // "```spec" 被切成三段，中间还夹着换行
        List<ChatStreamEvent> events = feed(List.of("看这里``", "`spe", "c\n{\"type\":\"text\"}\n``", "`尾巴"));

        assertThat(cardCount(events)).isEqualTo(1);
        assertThat(textOf(events)).isEqualTo("看这里尾巴");
    }

    @Test
    void 多个围栏依次产出多张卡片() {
        String full = "A\n```spec\n{\"type\":\"card\"}\n```\nB\n```spec\n{\"type\":\"text\"}\n```\nC";

        List<ChatStreamEvent> events = feed(List.of(full));

        assertThat(cardCount(events)).isEqualTo(2);
        assertThat(textOf(events)).contains("A").contains("B").contains("C");
    }

    @Test
    void 未闭合围栏在flush时整段回退为文本() {
        // LLM 被截断：残余必须原样（含 ```spec 标记）回退为文本，与 UiSpecService 处理 after 同语义
        String full = "开头\n```spec\n{\"type\":\"car";

        List<ChatStreamEvent> events = feed(List.of(full));

        assertThat(cardCount(events)).isZero();
        assertThat(textOf(events)).isEqualTo(full);
    }

    @Test
    void 围栏内JSON非法时回退为文本不抛异常() {
        String full = "开头\n```spec\n这不是JSON\n```\n结尾";

        List<ChatStreamEvent> events = feed(List.of(full));

        assertThat(cardCount(events)).isZero();
        assertThat(textOf(events)).contains("这不是JSON").contains("开头").contains("结尾");
    }

    @Test
    void spec后面不是换行时不当作围栏() {
        // "```specification" 只是普通文本，不能误判为围栏起始
        List<ChatStreamEvent> events = feed(List.of("见 ```specification 文档"));

        assertThat(cardCount(events)).isZero();
        assertThat(textOf(events)).isEqualTo("见 ```specification 文档");
    }

    @Test
    void 与UiSpecService解析结果保持一致() {
        // 不变量守护：渲染（splitter）与落库（UiSpecService.parse）不可漂移。
        // 说明：parse 会丢弃 isBlank 片段并对 plainText 做 trim，逐块流式不可能做全局 trim，
        // 故断言在 trim 后比较。
        UiSpecService uiSpecService = new UiSpecService(objectMapper);
        String full = "前言\n```spec\n{\"type\":\"card\",\"children\":[]}\n```\n后记";

        UiSpecService.ParseResult parsed = uiSpecService.parse(full);
        List<ChatStreamEvent> events = feedCharByChar(full);

        assertThat(textOf(events).trim()).isEqualTo(parsed.plainText());
        assertThat(cardCount(events))
                .isEqualTo(parsed.uiParts().stream().filter(p -> "ui".equals(p.getKind())).count());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn test -Dtest=StreamingSpecSplitterTest`
Expected: 编译失败，`StreamingSpecSplitter` 不存在。

- [ ] **Step 3: 实现切分器**

`backend/src/main/java/com/qingyu/care/service/StreamingSpecSplitter.java`：

```java
package com.qingyu.care.service;

import com.qingyu.care.dto.ChatStreamEvent;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 流式 ```spec 围栏增量切分：逐块喂入 LLM 文本，吐出 DELTA（围栏外文本）与 CARD（围栏闭合后的完整 UI 树）。
 *
 * <p><b>识别规则与 {@link UiSpecService#FENCE} 严格同源</b>：起始标记 ```spec 后跟可选空白 + 换行，
 * 结束标记 ```。两者一旦漂移，会出现「流式渲染成卡片、落库却是文本」的分裂。
 *
 * <p>fail-soft（延续 UiSpecService 铁律）：围栏内 JSON 非法 → 内容当文本吐出；流结束时围栏未闭合
 * → 连同起始标记原样回退为文本；任何情况都不抛异常阻断回复。
 *
 * <p>有状态，非线程安全：每条流用一个新实例（调用方用 Flux.defer 包裹创建）。
 *
 * @author ruan
 */
@Slf4j
public class StreamingSpecSplitter {

    private static final String FENCE_START = "```spec";
    private static final String FENCE_END = "```";

    private final ObjectMapper objectMapper;
    private final StringBuilder buf = new StringBuilder();

    /** 是否处于围栏内（围栏内的内容全部缓冲，不吐 DELTA）。 */
    private boolean inFence = false;
    /** 围栏起始标记的原文（含其后空白与换行）；未闭合时 flush 要原样吐回，保证与 parse 一致。 */
    private String openFenceRaw = "";

    public StreamingSpecSplitter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 喂入一块文本，返回本次可确定的事件（可能为空——数据还不足以决策时会继续缓冲）。 */
    public List<ChatStreamEvent> accept(String chunk) {
        List<ChatStreamEvent> events = new ArrayList<>();
        if (chunk == null || chunk.isEmpty()) {
            return events;
        }
        buf.append(chunk);
        boolean progressed = true;
        while (progressed) {
            progressed = inFence ? scanFenceEnd(events) : scanFenceStart(events);
        }
        return events;
    }

    /** 流结束：吐出全部残余（未闭合围栏连同起始标记原样回退为文本）。 */
    public List<ChatStreamEvent> flush() {
        List<ChatStreamEvent> events = new ArrayList<>();
        String rest = (inFence ? openFenceRaw : "") + buf;
        if (!rest.isEmpty()) {
            events.add(ChatStreamEvent.delta(rest));
        }
        buf.setLength(0);
        inFence = false;
        openFenceRaw = "";
        return events;
    }

    /**
     * 围栏外：找起始标记。返回是否取得进展（取得则继续循环扫描）。
     * 找不到时保留末尾可能被切断的部分标记（最多 FENCE_START.length()-1 个字符）等下一块。
     */
    private boolean scanFenceStart(List<ChatStreamEvent> events) {
        int idx = buf.indexOf(FENCE_START);
        if (idx < 0) {
            int keep = partialMarkerLen(buf, FENCE_START);
            if (buf.length() > keep) {
                emitText(events, buf.substring(0, buf.length() - keep));
                buf.delete(0, buf.length() - keep);
            }
            return false;
        }
        // 起始标记后必须是「可选空白 + 换行」，否则只是普通文本（如 ```specification）
        int scan = idx + FENCE_START.length();
        while (scan < buf.length() && isInlineWhitespace(buf.charAt(scan))) {
            scan++;
        }
        if (scan >= buf.length()) {
            // 还不能判定：先把标记之前的文本吐掉，标记本身留在缓冲等更多数据
            if (idx > 0) {
                emitText(events, buf.substring(0, idx));
                buf.delete(0, idx);
            }
            return false;
        }
        if (buf.charAt(scan) != '\n') {
            // 确定不是围栏：把「文本 + 起始标记」整体当文本吐出，继续找下一个
            emitText(events, buf.substring(0, idx + FENCE_START.length()));
            buf.delete(0, idx + FENCE_START.length());
            return true;
        }
        if (idx > 0) {
            emitText(events, buf.substring(0, idx));
        }
        openFenceRaw = buf.substring(idx, scan + 1);
        buf.delete(0, scan + 1);
        inFence = true;
        return true;
    }

    /** 围栏内：找结束标记。找到则产出 CARD（JSON 非法时 fail-soft 退文本）。 */
    private boolean scanFenceEnd(List<ChatStreamEvent> events) {
        int idx = buf.indexOf(FENCE_END);
        if (idx < 0) {
            return false;   // 围栏内容全部缓冲，等闭合
        }
        String json = buf.substring(0, idx).trim();
        buf.delete(0, idx + FENCE_END.length());
        inFence = false;
        openFenceRaw = "";
        try {
            events.add(ChatStreamEvent.card(objectMapper.readValue(json, Object.class)));
        } catch (Exception e) {
            log.debug("流式卡片围栏 JSON 非法，回退为文本: {}", e.getMessage());
            emitText(events, json);
        }
        return true;
    }

    private void emitText(List<ChatStreamEvent> events, String text) {
        if (!text.isEmpty()) {
            events.add(ChatStreamEvent.delta(text));
        }
    }

    /** 空白但不含换行（换行是围栏起始的终止符，不能被跳过）。 */
    private static boolean isInlineWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\r';
    }

    /**
     * buf 末尾与 marker 前缀的最长匹配长度（最多 marker.length()-1）。
     * 用于「起始标记被 chunk 切断」时保留悬而未决的尾巴，避免把半个标记当文本吐出去。
     */
    static int partialMarkerLen(CharSequence buf, String marker) {
        int max = Math.min(buf.length(), marker.length() - 1);
        for (int len = max; len > 0; len--) {
            boolean match = true;
            for (int i = 0; i < len; i++) {
                if (buf.charAt(buf.length() - len + i) != marker.charAt(i)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return len;
            }
        }
        return 0;
    }
}
```

同时把 `UiSpecService.FENCE` 的可见性从 `private static final` 改为 `static final`（同包，供 javadoc `@link` 引用与规则同源性检查）：

```java
    /** 闭合的 ```spec ... ``` 围栏；DOTALL 让 . 匹配换行，非贪婪取最近的闭合。
     *  StreamingSpecSplitter 的增量识别规则与此严格同源。 */
    static final Pattern FENCE = Pattern.compile("```spec\\s*\\r?\\n(.*?)```", Pattern.DOTALL);
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn test -Dtest=StreamingSpecSplitterTest`
Expected: 10 个测试全绿。若「逐字符喂入」用例失败，重点检查 `partialMarkerLen` 与 `scan >= buf.length()` 两个分支。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/StreamingSpecSplitter.java \
        backend/src/main/java/com/qingyu/care/service/UiSpecService.java \
        backend/src/test/java/com/qingyu/care/service/StreamingSpecSplitterTest.java
git commit -m "feat(backend): 流式 spec 围栏增量切分器（与 UiSpecService 规则同源）"
```

---

### Task 3: 流式模型故障回退 `streamWithFallback`

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/ModelRouterService.java`（在 `getChatClientForScenario` 附近新增方法）
- Test: `backend/src/test/java/com/qingyu/care/service/StreamWithFallbackChainTest.java`

**Interfaces:**
- Consumes: 无新依赖
- Produces:
  - `Flux<org.springframework.ai.chat.model.ChatResponse> ModelRouterService.streamWithFallback(String scenario, Function<ChatClient, Flux<ChatResponse>> streamRunner)` —— Task 5 使用
  - `static Flux<ChatResponse> ModelRouterService.streamWithFallbackChain(List<Supplier<Flux<ChatResponse>>> attempts)` —— 纯逻辑，本任务单测直接调用

**关键约束：只在首个 chunk 发出前才能换模型**——已经开吐的流无法重放。

- [ ] **Step 1: 写失败的测试**

`backend/src/test/java/com/qingyu/care/service/StreamWithFallbackChainTest.java`：

```java
package com.qingyu.care.service;

import com.qingyu.care.support.SpringAiResponses;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 单元测试：流式模型故障回退链（static 纯方法，免容器，同 meterContentStream 惯例）。
 *
 * 核心约束：只有在「首个 chunk 尚未发出」时才允许切换下一个模型——
 * 已经开吐的流无法重放，中途换模型会让用户看到两段互相矛盾的回复。
 *
 * @author ruan
 */
class StreamWithFallbackChainTest {

    private static String textOf(List<ChatResponse> responses) {
        StringBuilder sb = new StringBuilder();
        for (ChatResponse r : responses) {
            sb.append(r.getResult().getOutput().getText());
        }
        return sb.toString();
    }

    @Test
    void 首个模型立即失败时切换下一个() {
        AtomicInteger secondCalled = new AtomicInteger();
        List<Supplier<Flux<ChatResponse>>> attempts = List.of(
                () -> Flux.error(new RuntimeException("模型A挂了")),
                () -> {
                    secondCalled.incrementAndGet();
                    return Flux.just(SpringAiResponses.of("来自B", 10));
                });

        List<ChatResponse> got = ModelRouterService.streamWithFallbackChain(attempts)
                .collectList().block();

        assertThat(textOf(got)).isEqualTo("来自B");
        assertThat(secondCalled.get()).isEqualTo(1);
    }

    @Test
    void 已经吐出内容后失败不再切换而是透传错误() {
        List<Supplier<Flux<ChatResponse>>> attempts = List.of(
                () -> Flux.concat(
                        Flux.just(SpringAiResponses.of("半截", 0)),
                        Flux.error(new RuntimeException("中途断了"))),
                () -> Flux.just(SpringAiResponses.of("不该出现", 10)));

        assertThatThrownBy(() -> ModelRouterService.streamWithFallbackChain(attempts)
                .collectList().block())
                .hasMessageContaining("中途断了");
    }

    @Test
    void 所有模型都失败时抛最后一个错误() {
        List<Supplier<Flux<ChatResponse>>> attempts = List.of(
                () -> Flux.error(new RuntimeException("A挂")),
                () -> Flux.error(new RuntimeException("B挂")));

        assertThatThrownBy(() -> ModelRouterService.streamWithFallbackChain(attempts)
                .collectList().block())
                .hasMessageContaining("B挂");
    }

    @Test
    void 首个模型正常时不触碰后续模型() {
        AtomicInteger secondCalled = new AtomicInteger();
        List<Supplier<Flux<ChatResponse>>> attempts = List.of(
                () -> Flux.just(SpringAiResponses.of("来自A", 5)),
                () -> {
                    secondCalled.incrementAndGet();
                    return Flux.just(SpringAiResponses.of("来自B", 5));
                });

        List<ChatResponse> got = ModelRouterService.streamWithFallbackChain(attempts)
                .collectList().block();

        assertThat(textOf(got)).isEqualTo("来自A");
        assertThat(secondCalled.get()).isZero();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn test -Dtest=StreamWithFallbackChainTest`
Expected: 编译失败，`streamWithFallbackChain` 不存在。

- [ ] **Step 3: 实现回退链**

在 `ModelRouterService` 中 `getChatClientForScenario` 方法之后追加（需要补 import：`reactor.core.publisher.Flux`、`java.util.concurrent.atomic.AtomicBoolean`、`java.util.function.Function`、`java.util.function.Supplier`、`java.util.ArrayList`）：

```java
    /**
     * 流式调用 + 模型故障回退：按场景取模型列表依次尝试，
     * <b>只在首个 chunk 尚未发出时</b>才切换下一个模型（已经开吐的流无法重放）。
     * 与非流式 {@link #callWithFallback} 对应，让流式路径不再是单模型裸奔。
     */
    public Flux<org.springframework.ai.chat.model.ChatResponse> streamWithFallback(
            String scenario,
            Function<ChatClient, Flux<org.springframework.ai.chat.model.ChatResponse>> streamRunner) {
        List<AiModelConfig> models = getModelsForScenario(scenario);
        if (models.isEmpty()) {
            throw new RuntimeException("没有可用的 AI 模型配置");
        }
        // 模型列表与 ChatClient 的创建都在调用线程完成（依赖 TenantContext），
        // 只把「发起请求」延后到订阅时——订阅发生在 Reactor 线程，那时上下文已可能被清理。
        List<Supplier<Flux<org.springframework.ai.chat.model.ChatResponse>>> attempts = new ArrayList<>();
        for (AiModelConfig model : models) {
            ChatClient client = createChatClient(model);
            attempts.add(() -> {
                log.debug("Streaming with model: {} ({})", model.getName(), model.getModelId());
                return streamRunner.apply(client);
            });
        }
        return streamWithFallbackChain(attempts);
    }

    /**
     * 纯方法：按顺序尝试各次流式调用，仅在「尚未发出任何元素」时才回退到下一个。
     * static 免容器单测（同 ChatServiceImpl#meterContentStream 惯例）。
     */
    static Flux<org.springframework.ai.chat.model.ChatResponse> streamWithFallbackChain(
            List<Supplier<Flux<org.springframework.ai.chat.model.ChatResponse>>> attempts) {
        return attemptStream(attempts, 0);
    }

    private static Flux<org.springframework.ai.chat.model.ChatResponse> attemptStream(
            List<Supplier<Flux<org.springframework.ai.chat.model.ChatResponse>>> attempts, int index) {
        AtomicBoolean emitted = new AtomicBoolean(false);
        return Flux.defer(attempts.get(index)::get)
                .doOnNext(x -> emitted.set(true))
                .onErrorResume(e -> {
                    if (emitted.get() || index + 1 >= attempts.size()) {
                        return Flux.error(e);
                    }
                    log.warn("流式模型调用失败，切换下一个模型: {}", e.getMessage());
                    return attemptStream(attempts, index + 1);
                });
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn test -Dtest=StreamWithFallbackChainTest`
Expected: 4 个测试全绿。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/ModelRouterService.java \
        backend/src/test/java/com/qingyu/care/service/StreamWithFallbackChainTest.java
git commit -m "feat(backend): 流式模型故障回退（仅首 chunk 前可切换）"
```

---

### Task 4: `streamChat` 改为事件流（签名收敛 + 管道组装）

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/ChatService.java`（三个 `streamChat` 重载收敛为两个）
- Modify: `backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java:276-337`（`streamChat` 主体）、`:409-423`（`persistStreamedMessages`）
- Modify: `backend/src/test/java/com/qingyu/care/service/impl/ChatStreamPersistenceTest.java`（适配新签名）
- Modify: `backend/src/test/java/com/qingyu/care/service/impl/StreamChatHandoverTest.java`（适配事件流断言）
- Test: `backend/src/test/java/com/qingyu/care/service/impl/StreamChatEventPipelineTest.java`（新建）

**Interfaces:**
- Consumes: `ChatStreamEvent`（Task 1）、`StreamingSpecSplitter`（Task 2）
- Produces:
  - `Flux<ChatStreamEvent> ChatService.streamChat(ChatRequest)`
  - `Flux<ChatStreamEvent> ChatService.streamChat(ChatRequest, IntConsumer onTokensUsed)`
  - `ChatStreamEvent ChatServiceImpl.persistStreamedMessages(ChatSession, String userMessage, String rawResponse, double confidence, String hitDocumentIds)`（package-private，返回 DONE 事件）
  - Task 6 的控制器、Task 5 的语义补齐都建立在这些签名上。

**注意：本任务只改「形状」（返回类型 + 事件管道），不补语义缺口——那是 Task 5。**

- [ ] **Step 1: 写失败的测试**

`backend/src/test/java/com/qingyu/care/service/impl/StreamChatEventPipelineTest.java`：

```java
package com.qingyu.care.service.impl;

import com.qingyu.care.dto.ChatStreamEvent;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.service.AbTestService;
import com.qingyu.care.service.ChatStrategyService;
import com.qingyu.care.service.ChatToolService;
import com.qingyu.care.service.ConfigService;
import com.qingyu.care.service.EmbeddingModelProvider;
import com.qingyu.care.service.HandoverRuleService;
import com.qingyu.care.service.HybridRetrievalService;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.service.PromptTemplateService;
import com.qingyu.care.service.RetrievalScopeResolver;
import com.qingyu.care.service.UiSpecService;
import com.qingyu.care.service.realtime.RealtimeNotifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单元测试：流式落库产出 DONE 事件的三分支（正常/空回复/异常），
 * 以及「围栏原文落库时经 UiSpecService 解析、content 落纯文本 + ui_spec 落卡片 JSON」。
 *
 * 说明：DONE 事件由 persistStreamedMessages 直接返回（不再走 Sinks 回调），故可脱离 Reactor 直接断言。
 * UiSpecService/ObjectMapper 用真实实例（@Spy 包真对象）——卡片解析正是本用例要验证的行为，
 * mock 会让断言失去意义（同 ChatCardWiringTest 惯例）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class StreamChatEventPipelineTest {

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
    @Spy UiSpecService uiSpecService = new UiSpecService(new ObjectMapper());
    @Spy ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks ChatServiceImpl service;

    /** 事务模板直通：把 TransactionCallback 当普通函数调用，保持纯 Mockito 单测特性。 */
    private void stubTransactionPassThrough() {
        when(transactionTemplate.execute(any())).thenAnswer(inv ->
                inv.getArgument(0, TransactionCallback.class).doInTransaction(null));
    }

    /** 落库时给消息按序分配自增 id（第 1 条用户消息、第 2 条 AI 消息）。 */
    private void stubSaveWithIdSequence() {
        AtomicLong seq = new AtomicLong();
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            m.setId(seq.incrementAndGet());
            return m;
        });
    }

    @Test
    void 正常回复落库后DONE携带AI消息真实id() {
        stubTransactionPassThrough();
        stubSaveWithIdSequence();

        ChatStreamEvent done = service.persistStreamedMessages(
                ChatSession.builder().sessionId("s1").build(), "问题", "你好", 0.8, "[1,2]");

        assertThat(done.type()).isEqualTo(ChatStreamEvent.Type.DONE);
        // 第 2 次 save 是 AI 消息，id=2；DONE 必须带真实落库 id 而非占位值
        assertThat(((ChatStreamEvent.Done) done.payload()).messageId()).isEqualTo(2L);
    }

    @Test
    void 空回复不落库且DONE的消息id为null() {
        ChatStreamEvent done = service.persistStreamedMessages(
                ChatSession.builder().sessionId("s2").build(), "问题", "", 0.0, null);

        assertThat(((ChatStreamEvent.Done) done.payload()).messageId()).isNull();
        verify(messageRepository, never()).save(any());
    }

    @Test
    void 落库异常时fail_soft仍返回DONE不抛() {
        when(transactionTemplate.execute(any())).thenThrow(new RuntimeException("DB挂了"));

        ChatStreamEvent done = service.persistStreamedMessages(
                ChatSession.builder().sessionId("s3").build(), "问题", "你好", 0.5, null);

        assertThat(done.type()).isEqualTo(ChatStreamEvent.Type.DONE);
        assertThat(((ChatStreamEvent.Done) done.payload()).messageId()).isNull();
    }

    @Test
    void 含围栏的原文落库时content剥围栏且ui_spec非空() {
        stubTransactionPassThrough();
        stubSaveWithIdSequence();

        service.persistStreamedMessages(ChatSession.builder().sessionId("s4").build(), "问题",
                "前言\n```spec\n{\"type\":\"card\"}\n```\n后记", 0.9, null);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageRepository, times(2)).save(captor.capture());
        ChatMessage aiMsg = captor.getAllValues().stream()
                .filter(m -> m.getRole() == MessageRole.ASSISTANT)
                .findFirst().orElseThrow();
        assertThat(aiMsg.getContent()).doesNotContain("```").contains("前言").contains("后记");
        assertThat(aiMsg.getUiSpec()).contains("\"kind\"").contains("ui");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn test -Dtest=StreamChatEventPipelineTest`
Expected: 编译失败，`persistStreamedMessages` 签名不匹配（当前是 void + LongConsumer）。

- [ ] **Step 3: 改接口签名**

`ChatService.java` 中删除三个 `streamChat` 重载，替换为：

```java
    /**
     * 流式聊天（内部调用，不计量）。返回结构化事件流，协议见
     * docs/superpowers/specs/2026-08-04-streaming-chat-design.md §3。
     */
    Flux<ChatStreamEvent> streamChat(ChatRequest request);

    /**
     * 流式聊天（开放 API：带 token 计量）。流正常/异常结束时以本次 total_tokens 回调。
     * 事件序列：START → (DELTA|CARD)* → DONE；失败时以 ERROR 收尾。
     */
    Flux<ChatStreamEvent> streamChat(ChatRequest request, java.util.function.IntConsumer onTokensUsed);
```

补 import `com.qingyu.care.dto.ChatStreamEvent;`，删除不再需要的 `java.util.function.LongConsumer` 相关声明。

- [ ] **Step 4: 改 `ChatServiceImpl.streamChat`**

替换 `ChatServiceImpl.java:275-337` 的三个重载为：

```java
    @Override
    public Flux<ChatStreamEvent> streamChat(ChatRequest request) {
        // 内部调用：不计量，回调为空操作
        return streamChat(request, t -> {});
    }

    @Override
    public Flux<ChatStreamEvent> streamChat(ChatRequest request,
                                            java.util.function.IntConsumer onTokensUsed) {
        // 请求线程内完成所有依赖 TenantContext 的操作（会话创建/检索/模型解析）；
        // 落库回调运行在 Reactor 线程，只做无上下文依赖的写入
        ChatSession session = getOrCreateSession(request);

        // 与 chat() 严格同序：先判人工接管态静默，再判关键词触发，避免已接管会话被关键词打回 WAITING_AGENT
        if (isAgentControlled(session)) {
            persistUserMessageDuringHandover(session, request.getMessage());
            onTokensUsed.accept(0);
            return earlyReturn(session, "[会话已转接人工客服，请等待回复]");
        }
        if (triggerKeywordHandoverIfHit(session, request.getMessage())) {
            onTokensUsed.accept(0);
            return earlyReturn(session, "正在为您转接人工客服，请稍候...");
        }

        ChatStrategy strategy = resolveStrategy(session);
        // 同 chat()：经 self 走代理，检索在独立只读事务上执行
        List<Document> relevantDocs;
        try {
            relevantDocs = self.searchKnowledge(request.getMessage(), strategy);
        } catch (Exception e) {
            // 同 chat()：REQUIRES_NEW 的开启/提交事务阶段异常落在方法体 try 之外，此处兜底 fail-soft
            log.warn("检索独立事务开启/提交失败，降级为空知识: {}", e.getMessage());
            relevantDocs = List.of();
        }
        String context = buildContext(relevantDocs);
        // Task 5 会把 skillMarkdowns / withCards 换成与 chat() 一致的取值
        List<Message> messages = buildMessages(session, request.getMessage(), context, strategy,
                java.util.List.of(), false);

        double maxScore = relevantDocs.isEmpty() ? 0.0 :
                relevantDocs.stream().mapToDouble(ChatServiceImpl::resolveConfidenceScore).max().orElse(0.0);
        String hitDocumentIds = extractHitDocumentIds(relevantDocs);
        String userMessage = request.getMessage();

        // 场景解析读租户配置（依赖 TenantContext），须在请求线程完成，不能挪进响应式管道
        Flux<org.springframework.ai.chat.model.ChatResponse> source =
                modelRouterService.streamWithFallback(resolveModelScenario(),
                        client -> client.prompt().messages(messages).stream().chatResponse());

        // Task 5 会把 suggestHandover / knowledgeHits 换成真实计算值
        ChatStreamEvent startEvent = ChatStreamEvent.start(session.getSessionId(), false, List.of());

        return Flux.defer(() -> {
            // splitter 与累加器有状态，必须每次订阅各持一份，否则重复订阅会串状态
            StreamingSpecSplitter splitter = new StreamingSpecSplitter(objectMapper);
            StringBuilder acc = new StringBuilder();
            Flux<ChatStreamEvent> body = meterContentStream(source, onTokensUsed)
                    .doOnNext(acc::append)
                    .concatMap(chunk -> Flux.fromIterable(splitter.accept(chunk)))
                    .concatWith(Flux.defer(() -> Flux.fromIterable(splitter.flush())));
            // 落库排在正文之后（DONE 的 messageId 必然有效）；客户端中断时该 Mono 根本不会被订阅，
            // 半截回复自然不落库（与改造前 doOnComplete 语义一致）
            Mono<ChatStreamEvent> done = Mono.fromCallable(() -> persistStreamedMessages(
                    session, userMessage, acc.toString(), maxScore, hitDocumentIds));
            return Flux.concat(Flux.just(startEvent), body, done);
        }).onErrorResume(e -> {
            // 固定文案，绝不回传原始异常（widget 运行在客户网页里）；详情只进日志
            log.warn("流式对话失败 sessionId={}: {}", session.getSessionId(), e.getMessage(), e);
            return Flux.just(ChatStreamEvent.error("回复生成失败，请稍后重试"));
        });
    }

    /**
     * 早退分支（人工接管静默 / 关键词转人工）：同样发完整 START + DELTA + DONE 序列，
     * 否则客户端拿不到 sessionId。这两条路不落 AI 消息，故 DONE 的 messageId 为 null。
     * suggestHandover 恒 false——会话已在人工流程中，再提示「建议转人工」是噪声。
     */
    private Flux<ChatStreamEvent> earlyReturn(ChatSession session, String text) {
        return Flux.just(
                ChatStreamEvent.start(session.getSessionId(), false, List.of()),
                ChatStreamEvent.delta(text),
                ChatStreamEvent.done(null));
    }
```

补 import：`com.qingyu.care.dto.ChatStreamEvent`、`com.qingyu.care.service.StreamingSpecSplitter`、`reactor.core.publisher.Mono`。

删除 `withStreamPersistence` 静态方法（其职责已由 `doOnNext(acc::append)` + `concat` 的 Mono 承担）及其在 `ChatStreamPersistenceTest` 中的用例。

改写 `persistStreamedMessages`：

```java
    /**
     * 流式补落库（Reactor 线程执行：ChatMessage 不继承 BaseTenantEntity，persist 不依赖租户过滤器），
     * 并产出流末尾的 DONE 事件。
     * <p>落库走 {@link UiSpecService#parse}（与非流式 chat() 同一实现）：content 落剥围栏的纯文本、
     * ui_spec 落卡片 JSON——渲染侧的 StreamingSpecSplitter 只管何时发事件，不承担落库正确性。
     * <p>fail-soft：落库失败仅告警，返回 messageId 为 null 的 DONE，不影响已送达的回复。
     * <p>已知取舍：流式 AI 消息不落 tokenUsage（消息级 token 仅非流式；用量计量仍走 onTokensUsed → SysApiUsage）。
     * <p>事务原子性：Reactor 线程无代理事务生效，须显式用 TransactionTemplate 包裹 saveMessages。
     * package-private 便于同包单测（同 meterContentStream 惯例）。
     */
    ChatStreamEvent persistStreamedMessages(ChatSession session, String userMessage, String rawResponse,
                                            double confidence, String hitDocumentIds) {
        Long savedId = null;
        try {
            if (!rawResponse.isEmpty()) {
                UiSpecService.ParseResult parsed = uiSpecService.parse(rawResponse);
                String uiSpecJson = writeUiSpec(parsed.uiParts());
                ChatMessage aiMsg = transactionTemplate.execute(status ->
                        saveMessages(session, userMessage, parsed.plainText(), confidence,
                                hitDocumentIds, null, false, uiSpecJson));
                savedId = aiMsg.getId();
            }
        } catch (Exception e) {
            log.warn("流式消息落库失败 sessionId={}: {}", session.getSessionId(), e.getMessage());
        }
        return ChatStreamEvent.done(savedId);
    }
```

- [ ] **Step 5: 适配两个既有测试**

`ChatStreamPersistenceTest`：删除 `withStreamPersistence` 相关用例（该方法已移除），把 `persistStreamedMessages` 用例改为断言返回的 DONE 事件而非 `LongConsumer` 回调。

`StreamChatHandoverTest`：把 `.block()` 拿到的字符串断言改为事件断言，例如：

```java
        List<ChatStreamEvent> events = service.streamChat(request).collectList().block();

        assertThat(events).hasSize(3);
        assertThat(events.get(0).type()).isEqualTo(ChatStreamEvent.Type.START);
        assertThat(((ChatStreamEvent.Start) events.get(0).payload()).sessionId()).isEqualTo("s-1");
        assertThat(((ChatStreamEvent.Delta) events.get(1).payload()).text())
                .isEqualTo("[会话已转接人工客服，请等待回复]");
        assertThat(events.get(2).type()).isEqualTo(ChatStreamEvent.Type.DONE);
```

> 注意：该测试类的 `wireSelf()` 里有 `TenantContext.setTenantId(9L)`（平台管理员守卫要求），不要删。

- [ ] **Step 6: 运行全部后端单测**

Run: `cd backend && mvn test`
Expected: 全绿。`OpenChatController` 此时会编译失败——**这是预期的**，Task 6 修复它；若想让本任务独立可编译，可在本步先把 `OpenChatController.stream` 临时改为 `chatService.streamChat(req, ...).map(e -> ServerSentEvent.builder("").build())` 之类的占位，但**更推荐把 Task 4 与 Task 6 连续执行、一次提交**。若选择连续执行，请在 Task 6 完成后再运行本步。

- [ ] **Step 7: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/ChatService.java \
        backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java \
        backend/src/test/java/com/qingyu/care/service/impl/
git commit -m "refactor(backend): streamChat 改为结构化事件流，落库产出 DONE 事件"
```

---

### Task 5: `streamChat` 语义补齐（对齐 `chat()`）

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java`（Task 4 改造后的 `streamChat` 主体）
- Test: `backend/src/test/java/com/qingyu/care/service/impl/StreamChatParityTest.java`（新建）

**Interfaces:**
- Consumes: Task 4 的 `Flux<ChatStreamEvent> streamChat(...)`
- Produces: 无新签名；补齐后 `START` 事件的 `suggestHandover`/`knowledgeHits` 变为真实值，流式开始注入卡片 Prompt 与工具。

- [ ] **Step 1: 写失败的测试**

`backend/src/test/java/com/qingyu/care/service/impl/StreamChatParityTest.java`：

```java
package com.qingyu.care.service.impl;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.ChatRequest;
import com.qingyu.care.dto.ChatStreamEvent;
import com.qingyu.care.entity.ChatMessage;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.service.AbTestService;
import com.qingyu.care.service.ChatStrategyService;
import com.qingyu.care.service.ChatToolService;
import com.qingyu.care.service.ConfigService;
import com.qingyu.care.service.EmbeddingModelProvider;
import com.qingyu.care.service.HandoverRuleService;
import com.qingyu.care.service.HybridRetrievalService;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.service.PromptTemplateService;
import com.qingyu.care.service.RetrievalScopeResolver;
import com.qingyu.care.service.UiSpecService;
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
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单元测试：流式与非流式的语义对齐。改造前 streamChat 静默丢失了这里断言的每一项能力
 * （兜底回复 / 连续未命中计数 / 转人工建议 / 知识命中 / 工具与 Skill）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class StreamChatParityTest {

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
    @Spy UiSpecService uiSpecService = new UiSpecService(new ObjectMapper());
    @Spy ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks ChatServiceImpl service;

    @BeforeEach
    void seedTenantContext() {
        // streamChat 要求非空租户上下文（见 requireTenantContext）；夹具会话不设 tenantId，
        // 实体侧为 null → 归属判定恒放行，取值任意不影响断言（同 ChatCardWiringTest）
        TenantContext.setTenantId(1L);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    /** 检索结果由 self 代理返回；单测里把 self 换成受控 mock（同 ChatCardWiringTest）。 */
    private void stubRetrieval(List<Document> docs) {
        ChatServiceImpl selfMock = mock(ChatServiceImpl.class);
        when(selfMock.searchKnowledge(anyString(), any())).thenReturn(docs);
        service.setSelf(selfMock);
    }

    /** 策略：allowNoKnowledge 决定未命中时走兜底还是继续问模型。 */
    private void stubStrategy(boolean allowNoKnowledge) {
        when(abTestService.resolveVariantStrategy(any(), any())).thenReturn(Optional.empty());
        when(chatStrategyService.getActiveStrategy("default")).thenReturn(
                ChatStrategy.builder().name("默认").scenario("default")
                        .allowNoKnowledge(allowNoKnowledge).fallbackMessage("暂时无法回答").build());
    }

    /** 落库时按序分配自增 id（第 1 条用户消息、第 2 条 AI 消息）。 */
    private void stubSaveWithIdSequence() {
        AtomicLong seq = new AtomicLong();
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            m.setId(seq.incrementAndGet());
            return m;
        });
    }

    /** 走到 LLM 分支所需的其余装配（模板 / 历史 / 场景 / 事务 / 模型流）。 */
    private void stubLlmPath(String reply, ChatToolService.ToolBundle bundle) {
        when(chatToolService.resolve()).thenReturn(bundle);
        when(promptTemplateService.getActiveTemplateContent()).thenReturn("你是轻语客服 {context}");
        when(messageRepository.findTop20BySessionIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(configService.get(any(), any(), any(), any())).thenReturn("default");
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv ->
                inv.getArgument(0, TransactionCallback.class).doInTransaction(null));
        when(modelRouterService.streamWithFallback(eq("default"), any()))
                .thenReturn(Flux.just(SpringAiResponses.of(reply, 7)));
    }

    private ChatStreamEvent.Start startOf(List<ChatStreamEvent> events) {
        return (ChatStreamEvent.Start) events.get(0).payload();
    }

    @Test
    void 未命中且不许自由发挥时流式也走兜底回复且不问模型() {
        stubRetrieval(List.of());
        stubStrategy(false);
        stubSaveWithIdSequence();
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(handoverRuleService.getNoHitStreakThreshold()).thenReturn(Integer.MAX_VALUE);

        List<ChatStreamEvent> events = service.streamChat(
                ChatRequest.builder().message("你好").build()).collectList().block();

        assertThat(events).hasSize(3);
        assertThat(events.get(0).type()).isEqualTo(ChatStreamEvent.Type.START);
        assertThat(((ChatStreamEvent.Delta) events.get(1).payload()).text()).isEqualTo("暂时无法回答");
        assertThat(((ChatStreamEvent.Done) events.get(2).payload()).messageId()).isEqualTo(2L);
        verify(modelRouterService, never()).streamWithFallback(any(), any());
    }

    @Test
    void 检索未命中时连续未命中计数递增并落库() {
        ChatSession session = ChatSession.builder().sessionId("s1")
                .status(SessionStatus.AI_PROCESSING).noHitStreak(2).build();
        when(sessionRepository.findBySessionId("s1")).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));
        stubRetrieval(List.of());
        stubStrategy(false);
        stubSaveWithIdSequence();
        when(handoverRuleService.getNoHitStreakThreshold()).thenReturn(Integer.MAX_VALUE);

        service.streamChat(ChatRequest.builder().sessionId("s1").message("你好").build())
                .collectList().block();

        assertThat(session.getNoHitStreak()).isEqualTo(3);
        verify(sessionRepository).save(session);
    }

    @Test
    void 检索命中时连续未命中计数归零() {
        ChatSession session = ChatSession.builder().sessionId("s2")
                .status(SessionStatus.AI_PROCESSING).noHitStreak(3).build();
        when(sessionRepository.findBySessionId("s2")).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        stubRetrieval(List.of(new Document("知识内容")));
        stubStrategy(true);
        stubLlmPath("回答", ChatToolService.ToolBundle.empty());

        service.streamChat(ChatRequest.builder().sessionId("s2").message("你好").build())
                .collectList().block();

        assertThat(session.getNoHitStreak()).isZero();
    }

    @Test
    void START携带低置信触发的转人工建议() {
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        stubRetrieval(List.of(new Document("知识内容")));
        stubStrategy(true);
        stubLlmPath("回答", ChatToolService.ToolBundle.empty());
        when(handoverRuleService.checkLowConfidenceTrigger(anyDouble())).thenReturn(true);

        List<ChatStreamEvent> events = service.streamChat(
                ChatRequest.builder().message("你好").build()).collectList().block();

        assertThat(startOf(events).suggestHandover()).isTrue();
    }

    @Test
    void START携带知识命中列表() {
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        stubRetrieval(List.of(new Document("知识A"), new Document("知识B")));
        stubStrategy(true);
        stubLlmPath("回答", ChatToolService.ToolBundle.empty());

        List<ChatStreamEvent> events = service.streamChat(
                ChatRequest.builder().message("你好").build()).collectList().block();

        assertThat(startOf(events).knowledgeHits()).hasSize(2);
        assertThat(startOf(events).knowledgeHits().get(0).getContent()).contains("知识A");
        assertThat(startOf(events).knowledgeHits().get(0).getSource()).isEqualTo("unknown");
    }

    @Test
    void 流式解析工具与skill并挂载到模型调用() {
        // 改造前 streamChat 传 List.of() 且从不调用 chatToolService —— 工具与 Skill 在流式下静默失效
        ToolCallback tool = mock(ToolCallback.class);
        when(sessionRepository.save(any(ChatSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        stubRetrieval(List.of(new Document("知识内容")));
        stubStrategy(true);
        stubLlmPath("好的", new ChatToolService.ToolBundle(List.of("# 退货政策"), List.of(tool)));

        // 捕获传给 streamWithFallback 的 runner，用深度桩 ChatClient 执行它，
        // 确认「有工具时挂载 toolCallbacks」这条链能真正跑通（不抛、不 NPE）
        var runnerCaptor = org.mockito.ArgumentCaptor.forClass(java.util.function.Function.class);

        service.streamChat(ChatRequest.builder().message("怎么退货").build()).collectList().block();

        verify(chatToolService).resolve();
        verify(modelRouterService).streamWithFallback(eq("default"), runnerCaptor.capture());
        org.springframework.ai.chat.client.ChatClient client =
                mock(org.springframework.ai.chat.client.ChatClient.class, RETURNS_DEEP_STUBS);
        assertThat(runnerCaptor.getValue().apply(client)).isNotNull();
    }
}
```

> 说明：最后一个用例只断言「工具被解析 + 挂载链能跑通」，不去 verify Spring AI fluent API 的每一环（深度桩对 fluent 接口的链式断言极脆，一次库升级就会假红）。**工具在流式下真的被执行**由 Task 12 冒烟第 5 条兜底——那也是本次唯一无法自动化验证的未知数，已在设计文档 §7 列为首要风险。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn test -Dtest=StreamChatParityTest`
Expected: FAIL——当前 `START` 的 `suggestHandover` 恒 false、`knowledgeHits` 恒空、无兜底分支、不注入工具。

- [ ] **Step 3: 补齐语义**

在 Task 4 的 `streamChat` 中，把 `maxScore` 计算之后到 `source` 之前的部分替换为：

```java
        double maxScore = relevantDocs.isEmpty() ? 0.0 :
                relevantDocs.stream().mapToDouble(ChatServiceImpl::resolveConfidenceScore).max().orElse(0.0);

        // 连续未命中转人工：与 chat() 同步更新会话计数（命中归 0），显式落库。
        // 存量行（加列前创建）读回 no_hit_streak 可能为 null，null 归 0 兜底防拆箱 NPE
        boolean noHit = relevantDocs.isEmpty();
        int currentStreak = session.getNoHitStreak() == null ? 0 : session.getNoHitStreak();
        session.setNoHitStreak(noHit ? currentStreak + 1 : 0);
        sessionRepository.save(session);
        boolean noHitStreakHandover = noHit
                && session.getNoHitStreak() >= handoverRuleService.getNoHitStreakThreshold();

        // 未命中且不允许自由发挥：与 chat() 一致走兜底回复（兜底也落库：fallback 标记 + messageId 回传）
        if (relevantDocs.isEmpty() && Boolean.FALSE.equals(strategy.getAllowNoKnowledge())) {
            onTokensUsed.accept(0);
            ChatMessage fallbackMsg = saveMessages(session, request.getMessage(),
                    strategy.getFallbackMessage(), 0.0, null, null, true, null);
            return Flux.just(
                    ChatStreamEvent.start(session.getSessionId(), noHitStreakHandover, List.of()),
                    ChatStreamEvent.delta(strategy.getFallbackMessage()),
                    ChatStreamEvent.done(fallbackMsg.getId()));
        }

        String context = buildContext(relevantDocs);
        // 解析当前应用的 skill/工具（请求线程内，依赖 TenantContext）——与 chat() 同源
        ChatToolService.ToolBundle toolBundle = chatToolService.resolve();
        // withCards=true：流式同样注入卡片能力 Prompt 片段，由 StreamingSpecSplitter 增量下发
        List<Message> messages = buildMessages(session, request.getMessage(), context, strategy,
                toolBundle.skillMarkdowns(), true);

        String hitDocumentIds = extractHitDocumentIds(relevantDocs);
        String userMessage = request.getMessage();
        boolean suggestHandover =
                handoverRuleService.checkLowConfidenceTrigger(maxScore) || noHitStreakHandover;
        List<ChatResponse.KnowledgeHit> knowledgeHits = relevantDocs.stream()
                .map(doc -> ChatResponse.KnowledgeHit.builder()
                        .content(truncateContent(doc.getText(), 100))
                        .score(resolveConfidenceScore(doc))
                        .source((String) doc.getMetadata().getOrDefault("source", "unknown"))
                        .build())
                .collect(Collectors.toList());

        final List<ToolCallback> tools = toolBundle.toolCallbacks();
        Flux<org.springframework.ai.chat.model.ChatResponse> source =
                modelRouterService.streamWithFallback(resolveModelScenario(), client -> {
                    // 不要在这里 .options(...)，会整体替换掉模型默认 options（含 streamUsage(true)，
                    // 丢失后 token 计量会静默记 0），见 ModelRouterService#createOpenAiCompatibleClient
                    var spec = client.prompt().messages(messages);
                    if (!tools.isEmpty()) {
                        spec = spec.toolCallbacks(tools);   // 无工具时不挂载 → 与 chat() 同款处理
                    }
                    return spec.stream().chatResponse();
                });

        ChatStreamEvent startEvent =
                ChatStreamEvent.start(session.getSessionId(), suggestHandover, knowledgeHits);
```

同时把 `buildMessages` 与 `appendCardPrompt` 的过时注释改掉（`ChatServiceImpl:84`、`:91`、`:216`、`:318`、`:746`、`:770` 附近都写着「streamChat 恒不注入 / 流式卡片留 SP3」）：

```java
    // 结构化卡片：是否向系统 Prompt 注入卡片能力片段（流式与非流式均注入；
    // 流式由 StreamingSpecSplitter 在围栏闭合时增量下发 CARD 事件）
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn test -Dtest=StreamChatParityTest`
Expected: 6 个测试全绿。

- [ ] **Step 5: 跑全量单测确认无回归**

Run: `cd backend && mvn test`
Expected: 全绿（`ChatCardWiringTest`、`ChatServiceNoHitStreakTest`、`ChatServiceSkillPromptTest` 等既有用例若断言了「流式不注入卡片/工具」，需按新语义更新断言并在提交信息里说明）。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/service/impl/ChatServiceImpl.java \
        backend/src/test/java/com/qingyu/care/service/impl/StreamChatParityTest.java
git commit -m "feat(backend): 流式对齐非流式语义（兜底/未命中计数/转人工/命中/工具/Skill/卡片）"
```

---

### Task 6: 两个控制器改用事件流

**Files:**
- Modify: `backend/src/main/java/com/qingyu/care/controller/ChatController.java:46-51`
- Modify: `backend/src/main/java/com/qingyu/care/controller/open/OpenChatController.java:61-84`

**Interfaces:**
- Consumes: `ChatService.streamChat(...)`（Task 4/5）、`ChatStreamSseMapper.toSse`（Task 1）
- Produces: `POST /v1/chat/stream` 与 `POST /open/v1/chat/stream` 两个 SSE 端点，同协议。Task 7 的 IT、Task 8/10 的客户端依赖它们。

- [ ] **Step 1: 改管理端控制器**

`ChatController.java`：注入 mapper 并改造端点。

```java
    private final ChatStreamSseMapper sseMapper;   // 加入 final 字段（@RequiredArgsConstructor 自动注入）

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式聊天", description = "SSE 事件流：start/delta/card/done/error")
    public Flux<ServerSentEvent<String>> streamChat(@Valid @RequestBody ChatRequest request) {
        request.setOpenChannel(false);
        // 管理后台不计量（token 计量是开放 API 专属）
        return chatService.streamChat(request).map(sseMapper::toSse);
    }
```

补 import `org.springframework.http.codec.ServerSentEvent;`、`com.qingyu.care.service.ChatStreamSseMapper;`。

- [ ] **Step 2: 改开放端控制器**

`OpenChatController.java`：删除 `Sinks`/`Mono` 那套绕法，改为：

```java
    /**
     * 流式对话（SSE）。事件协议见 docs/superpowers/specs/2026-08-04-streaming-chat-design.md §3：
     * start（会话/转人工建议/知识命中）→ delta|card（正文增量与卡片）→ done（messageId）；失败以 error 收尾。
     * 流结束时经计量回调异步记录用量（计量保持开放 API 专属）。
     * POST /open/v1/chat/stream
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@Valid @RequestBody OpenChatRequest req) {
        // 与 completions 一致：传了 sessionId 但服务端已不存在/过期 → 显式 404，
        // 供 Widget 清本地会话后自动重开（改造前流式端点漏了这一步）
        if (req.getSessionId() != null && !req.getSessionId().isBlank()
                && !chatService.sessionExists(req.getSessionId())) {
            throw new OpenApiException(404, "会话不存在或已过期");
        }
        // 必须在请求线程（Reactor 订阅前）捕获上下文，回调运行在非 HTTP 线程中无上下文
        final Long tid = TenantContext.getTenantId();
        final Long aid = TenantContext.getAppId();
        return chatService.streamChat(toInternal(req, true),
                        tokens -> apiUsageService.record(tid, aid, 1L, tokens))
                .map(sseMapper::toSse);
    }
```

注入 `private final ChatStreamSseMapper sseMapper;`；删除 `reactor.core.publisher.Mono`、`reactor.core.publisher.Sinks` 两个 import。

- [ ] **Step 3: 编译 + 全量单测**

Run: `cd backend && mvn test`
Expected: 全绿。

- [ ] **Step 4: 提交**

```bash
git add backend/src/main/java/com/qingyu/care/controller/ChatController.java \
        backend/src/main/java/com/qingyu/care/controller/open/OpenChatController.java
git commit -m "feat(backend): 两个流式端点改用统一 SSE 事件协议（开放端补会话过期 404）"
```

---

### Task 7: SSE 端到端集成测试

**Files:**
- Create: `backend/src/test/java/com/qingyu/care/it/ChatStreamEventIT.java`

**Interfaces:**
- Consumes: `POST /open/v1/chat/stream`（Task 6）、`ChatStreamEvent`（Task 1）
- Produces: 无（验证性任务）

- [ ] **Step 1: 写 IT**

```java
package com.qingyu.care.it;

import com.qingyu.care.dto.ChatStreamEvent;
import com.qingyu.care.service.ChatService;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 集成测试：流式端点的 SSE 线格式契约（事件名 + JSON payload + 顺序）。
 *
 * ChatService 被 mock 成有限事件流——本 IT 只验证「控制器 + SseMapper + Spring MVC SSE 编码」
 * 这一段契约；真实对话语义由 StreamChatParityTest 等单测覆盖。
 * 有限流会自然完成，不会像 RealtimeSseIT 那样挂起。
 *
 * @author ruan
 */
class ChatStreamEventIT extends AbstractPostgresIT {

    @Autowired MockMvc mockMvc;

    @MockitoBean ChatService chatService;

    @Test
    void 开放端流式返回start_delta_card_done四类事件() throws Exception {
        seedApp("stream-it-app", "stream-it-secret");

        when(chatService.streamChat(any(), any())).thenReturn(Flux.just(
                ChatStreamEvent.start("s_it", true, List.of()),
                ChatStreamEvent.delta("你好\n世界"),
                ChatStreamEvent.card(java.util.Map.of("type", "card")),
                ChatStreamEvent.done(42L)));

        MvcResult started = mockMvc.perform(post("/open/v1/chat/stream")
                        .header("X-App-Id", "stream-it-app")
                        .header("X-Api-Key", "stream-it-secret")
                        .contentType("application/json")
                        .content("{\"message\":\"你好\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("event:start").contains("\"sessionId\":\"s_it\"")
                .contains("event:delta").contains("event:card").contains("event:done")
                .contains("\"messageId\":42");
        // delta 里的换行必须已被 JSON 转义（SSE 层保持单行）
        assertThat(body).contains("\\n世界");
        // 事件顺序
        assertThat(body.indexOf("event:start")).isLessThan(body.indexOf("event:delta"));
        assertThat(body.indexOf("event:card")).isLessThan(body.indexOf("event:done"));
    }
}
```

> 实现者注意：`seedApp(...)` 的签名以 `AbstractPostgresIT` 现有实现为准（参考 `OpenApiAuthIT` 的调用方式），鉴权头也照该 IT 的写法对齐。若 `event:start` 断言因 Spring 编码为 `event:start\n` 或 `event: start` 而失败，按实际输出调整断言字符串（两种写法都合法，以实测为准）。

- [ ] **Step 2: 运行 IT**

Run: `cd backend && mvn verify -Dit.test=ChatStreamEventIT`（需 Docker Desktop）
Expected: 绿。

- [ ] **Step 3: 提交**

```bash
git add backend/src/test/java/com/qingyu/care/it/ChatStreamEventIT.java
git commit -m "test(backend): 流式端点 SSE 事件契约集成测试"
```

---

### Task 8: 前端流式客户端 `chatStream.ts`

**Files:**
- Create: `frontend/src/lib/chatStream.ts`
- Modify: `frontend/src/lib/sse.ts`（导出 `authHeaders` 供复用）

**Interfaces:**
- Consumes: `POST /api/v1/chat/stream`（Task 6）
- Produces: `streamChat(body: ChatStreamRequest, handlers: ChatStreamHandlers, signal?: AbortSignal): Promise<void>`，其中
  `ChatStreamHandlers = { onStart?, onDelta?, onCard?, onDone?, onError? }`。Task 9 使用。

> 前端仓库无 vitest，本任务靠 `pnpm build` + Task 9 真机验证。逻辑保持极薄。

- [ ] **Step 1: 导出 `authHeaders`**

`frontend/src/lib/sse.ts` 中把 `function authHeaders()` 改为 `export function authHeaders()`，并把注释补一句「chatStream.ts 复用」。

- [ ] **Step 2: 写 `chatStream.ts`**

```ts
// frontend/src/lib/chatStream.ts
// 对话流专用的一次性 POST SSE：与 sse.ts（GET + 自动重连，inbox 长连接用）刻意分开——
// 对话流绝不能自动重连，重连等于重复发消息、重复计费。
// 事件协议见 docs/superpowers/specs/2026-08-04-streaming-chat-design.md §3。

import { authHeaders, parseSseChunk } from './sse';
import type { KnowledgeHit, UiNode } from '@/types';

export interface ChatStreamStart {
  sessionId: string;
  suggestHandover: boolean;
  knowledgeHits: KnowledgeHit[];
}

export interface ChatStreamHandlers {
  onStart?: (d: ChatStreamStart) => void;
  onDelta?: (text: string) => void;
  onCard?: (node: UiNode) => void;
  onDone?: (messageId: number | null) => void;
  /** 后端 error 事件，或流未以 done 结束（中断）。文案已可直接展示。 */
  onError?: (message: string) => void;
}

export interface ChatStreamRequest {
  userId: string;
  sessionId?: string;
  message: string;
}

/**
 * 发起一次流式对话并按事件分派。正常结束或出错都会 resolve（错误经 onError 回调），
 * 只有 abort 会以 AbortError reject —— 调用方据此区分「用户主动停止」与「服务端出错」。
 */
export async function streamChat(
  body: ChatStreamRequest,
  handlers: ChatStreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  let done = false;
  let errored = false;

  const resp = await fetch('/api/v1/chat/stream', {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  });

  if (!resp.ok || !resp.body) {
    handlers.onError?.(resp.status === 429 ? '请求过于频繁，请稍后再试。' : '回复生成失败，请稍后重试。');
    return;
  }

  const reader = resp.body.getReader();
  const decoder = new TextDecoder();
  let buf = '';
  for (;;) {
    const { done: finished, value } = await reader.read();
    if (finished) break;
    buf += decoder.decode(value, { stream: true });
    let idx: number;
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const chunk = buf.slice(0, idx);
      buf = buf.slice(idx + 2);
      const { event, data } = parseSseChunk(chunk);
      if (data === null) continue; // 心跳/注释
      let payload: any;
      try {
        payload = JSON.parse(data);
      } catch {
        continue; // 非法负载忽略，不打断整条流
      }
      if (event === 'start') handlers.onStart?.(payload);
      else if (event === 'delta') handlers.onDelta?.(payload.text ?? '');
      else if (event === 'card') { if (payload.node) handlers.onCard?.(payload.node); }
      else if (event === 'done') { done = true; handlers.onDone?.(payload.messageId ?? null); }
      else if (event === 'error') { errored = true; handlers.onError?.(payload.message || '回复生成失败。'); }
    }
  }

  // 流断了但既没收到 done 也没收到 error → 视为中断（半截文本保留在屏上，不重发）
  if (!done && !errored) handlers.onError?.('回复中断，请重试。');
}
```

- [ ] **Step 3: 类型补充**

确认 `frontend/src/types/index.ts` 已导出 `KnowledgeHit` 与 `UiNode`（`UiNode` 在第 58 行已有；`KnowledgeHit` 若未单独导出则补一个 `export interface KnowledgeHit { content: string; score: number; source: string }`）。

- [ ] **Step 4: 构建校验**

Run: `cd frontend && pnpm build && pnpm lint`
Expected: 构建通过；lint 无新增错误（既有基线 71 个 warning 不算新增）。

- [ ] **Step 5: 提交**

```bash
git add frontend/src/lib/chatStream.ts frontend/src/lib/sse.ts frontend/src/types/index.ts
git commit -m "feat(frontend): 对话流专用一次性 POST SSE 客户端"
```

---

### Task 9: `ChatPage` 流式接线 + 停止生成

**Files:**
- Modify: `frontend/src/pages/ChatPage.tsx`

**Interfaces:**
- Consumes: `streamChat`（Task 8）、既有 `JsonRender` / `CardErrorBoundary` / `saveChatSession`
- Produces: 无（页面级改动）

- [ ] **Step 1: 改写 `send`**

把 `send` 从「POST 等整包」改为流式；核心是维护一条占位 ASSISTANT 消息，用 `uiParts` 承载文本/卡片交替块：

```tsx
    const abortRef = useRef<AbortController | null>(null);

    /** 往占位消息追加文本：末尾是 text 片段就接上去，否则新开一块（卡片之后的文本另起一块）。 */
    const appendDelta = (msgId: number, text: string) => {
        setMessages((prev) => prev.map((m) => {
            if (m.id !== msgId) return m;
            const parts = [...(m.uiParts ?? [])];
            const last = parts[parts.length - 1];
            if (last && last.kind === 'text') parts[parts.length - 1] = { ...last, text: (last.text ?? '') + text };
            else parts.push({ kind: 'text', text });
            return { ...m, content: m.content + text, uiParts: parts };
        }));
    };

    const appendCard = (msgId: number, node: UiNode) => {
        setMessages((prev) => prev.map((m) =>
            m.id !== msgId ? m : { ...m, uiParts: [...(m.uiParts ?? []), { kind: 'ui', node }] }));
    };

    const send = async (text: string) => {
        if (!text.trim() || isLoading) return;

        const now = Date.now();
        const userMessage: ChatMessage = {
            id: now, role: 'USER', content: text, createdAt: new Date().toISOString(),
        };
        const placeholderId = now + 1;
        const placeholder: ChatMessage = {
            id: placeholderId, role: 'ASSISTANT', content: '', createdAt: new Date().toISOString(), uiParts: [],
        };

        setMessages((prev) => [...prev, userMessage, placeholder]);
        setInput('');
        setIsLoading(true);

        const controller = new AbortController();
        abortRef.current = controller;

        try {
            await streamChat({ userId, sessionId, message: text }, {
                onStart: (d) => {
                    setSessionId(d.sessionId);
                    saveChatSession(userId, d.sessionId);
                    setSuggestHandover(d.suggestHandover);
                    if (d.knowledgeHits?.length) {
                        setKnowledgeHitsMap((prev) => ({ ...prev, [placeholderId]: d.knowledgeHits }));
                    }
                },
                onDelta: (t) => appendDelta(placeholderId, t),
                onCard: (n) => appendCard(placeholderId, n),
                onDone: (messageId) => {
                    if (messageId == null) return;
                    // 占位 id 换成真实 messageId；知识命中以消息 id 为 key，必须一并迁移
                    setMessages((prev) => prev.map((m) => (m.id === placeholderId ? { ...m, id: messageId } : m)));
                    setKnowledgeHitsMap((prev) => {
                        if (!prev[placeholderId]) return prev;
                        const { [placeholderId]: hits, ...rest } = prev;
                        return { ...rest, [messageId]: hits };
                    });
                },
                onError: (msg) => appendDelta(placeholderId, `\n\n${msg}`),
            }, controller.signal);
        } catch (error: any) {
            if (error?.name !== 'AbortError') {
                toast({ variant: 'destructive', title: '发送失败', description: error?.message });
            }
        } finally {
            abortRef.current = null;
            setIsLoading(false);
        }
    };

    const handleStop = () => abortRef.current?.abort();
```

补 import：`import { streamChat } from '@/lib/chatStream';`、`import type { UiNode } from '@/types';`。

- [ ] **Step 2: 发送按钮改为「发送/停止」双态**

把 `ChatPage.tsx:287-293` 的发送按钮替换为：

```tsx
                                <Button
                                    onClick={isLoading ? handleStop : handleSend}
                                    disabled={!isLoading && !input.trim()}
                                    variant={isLoading ? 'destructive' : 'default'}
                                    className="h-12 w-12 rounded-xl shadow-lg transition-transform active:scale-95"
                                    title={isLoading ? '停止生成' : '发送'}
                                >
                                    {isLoading ? <Square className="w-4 h-4" /> : <Send className="w-5 h-5" />}
                                </Button>
```

在 lucide 引入里加 `Square`。注意：**停止后半截回复不会落库**（后端 cancel 不落库），刷新即消失——这与设计一致，无需额外提示。

- [ ] **Step 3: 更新能力概览文案**

`ChatPage.tsx:358` 的 `流式响应 (即将推出)` 改为 `流式响应与生成式卡片`。

- [ ] **Step 4: 构建校验**

Run: `cd frontend && pnpm build && pnpm lint`
Expected: 通过。

- [ ] **Step 5: 提交**

```bash
git add frontend/src/pages/ChatPage.tsx
git commit -m "feat(frontend): 管理端聊天改流式增量渲染 + 停止生成"
```

---

### Task 10: widget 流式客户端

**Files:**
- Create: `widget/src/chatStream.ts`
- Create: `widget/src/chatStream.test.ts`
- Modify: `widget/src/api.ts`（新增 `stream` 方法）

**Interfaces:**
- Consumes: `POST /open/v1/chat/stream`（Task 6）、既有 `parseSseChunk`（`widget/src/sse.ts`）
- Produces:
  - `postSse(url: string, headers: Record<string,string>, body: unknown, handlers: ChatStreamHandlers, signal?: AbortSignal): Promise<void>`
  - `api.stream(message: string, sessionId: string | null, handlers: ChatStreamHandlers): Promise<void>`（404 时抛 `SessionExpiredError`）

- [ ] **Step 1: 写失败的测试**

`widget/src/chatStream.test.ts`：

```ts
import { describe, it, expect, vi, afterEach } from 'vitest';
import { postSse } from './chatStream';

/** 用可控的 ReadableStream 伪造一个 SSE 响应 */
function mockSseResponse(chunks: string[], status = 200) {
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      const enc = new TextEncoder();
      for (const c of chunks) controller.enqueue(enc.encode(c));
      controller.close();
    },
  });
  return new Response(status === 200 ? body : null, { status });
}

afterEach(() => vi.unstubAllGlobals());

describe('postSse', () => {
  it('按事件类型分派 start/delta/card/done', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockSseResponse([
      'event:start\ndata: {"sessionId":"s1","suggestHandover":false,"knowledgeHits":[]}\n\n',
      'event:delta\ndata: {"text":"你好"}\n\n',
      'event:card\ndata: {"node":{"type":"card"}}\n\n',
      'event:done\ndata: {"messageId":9}\n\n',
    ])));
    const seen: string[] = [];
    let sid = '';
    let mid: number | null = null;

    await postSse('/x', {}, {}, {
      onStart: (d) => { sid = d.sessionId; seen.push('start'); },
      onDelta: (t) => seen.push('delta:' + t),
      onCard: () => seen.push('card'),
      onDone: (m) => { mid = m; seen.push('done'); },
    });

    expect(seen).toEqual(['start', 'delta:你好', 'card', 'done']);
    expect(sid).toBe('s1');
    expect(mid).toBe(9);
  });

  it('跨 chunk 切断的事件块能正确拼回', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockSseResponse([
      'event:del', 'ta\ndata: {"te', 'xt":"拼回来了"}\n\n',
      'event:done\ndata: {"messageId":1}\n\n',
    ])));
    const texts: string[] = [];

    await postSse('/x', {}, {}, { onDelta: (t) => texts.push(t) });

    expect(texts).toEqual(['拼回来了']);
  });

  it('流未以 done 结束时报告中断', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockSseResponse([
      'event:delta\ndata: {"text":"半截"}\n\n',
    ])));
    const errors: string[] = [];

    await postSse('/x', {}, {}, { onDelta: () => {}, onError: (m) => errors.push(m) });

    expect(errors).toHaveLength(1);
  });

  it('后端 error 事件透传文案且不再报中断', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockSseResponse([
      'event:error\ndata: {"message":"回复生成失败，请稍后重试"}\n\n',
    ])));
    const errors: string[] = [];

    await postSse('/x', {}, {}, { onError: (m) => errors.push(m) });

    expect(errors).toEqual(['回复生成失败，请稍后重试']);
  });

  it('非法 JSON 负载被忽略且不中断整条流', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockSseResponse([
      'event:delta\ndata: 这不是JSON\n\n',
      'event:delta\ndata: {"text":"正常"}\n\n',
      'event:done\ndata: {"messageId":2}\n\n',
    ])));
    const texts: string[] = [];

    await postSse('/x', {}, {}, { onDelta: (t) => texts.push(t) });

    expect(texts).toEqual(['正常']);
  });
});
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd widget && pnpm test`
Expected: FAIL —— `./chatStream` 不存在。

- [ ] **Step 3: 实现 `chatStream.ts`**

```ts
// widget/src/chatStream.ts
// 对话流专用的一次性 POST SSE：与 sse.ts（GET + 自动重连，inbox 用）刻意分开——
// 对话流绝不能自动重连，重连等于重复发消息、重复计费。

import { parseSseChunk } from './sse';
import type { UiNode } from './api';

export interface ChatStreamStart {
  sessionId: string;
  suggestHandover: boolean;
  knowledgeHits: Array<{ content: string; score: number; source: string }>;
}

export interface ChatStreamHandlers {
  onStart?: (d: ChatStreamStart) => void;
  onDelta?: (text: string) => void;
  onCard?: (node: UiNode) => void;
  onDone?: (messageId: number | null) => void;
  /** 后端 error 事件，或流未以 done 结束（中断）。文案已可直接展示。 */
  onError?: (message: string) => void;
}

/** 状态码 → 可直接展示给终端用户的文案（不暴露内部细节）。 */
function httpErrorText(status: number): string {
  if (status === 429) return '请求过于频繁，请稍后再试。';
  return '抱歉，服务暂时不可用，请稍后再试。';
}

export async function postSse(
  url: string,
  headers: Record<string, string>,
  body: unknown,
  handlers: ChatStreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const resp = await fetch(url, {
    method: 'POST',
    headers: { ...headers, Accept: 'text/event-stream' },
    body: JSON.stringify(body),
    signal,
  });

  if (resp.status === 404) {
    const err = new Error('会话不存在或已过期');
    err.name = 'SessionExpiredError';
    throw err;
  }
  if (!resp.ok || !resp.body) {
    handlers.onError?.(httpErrorText(resp.status));
    return;
  }

  let done = false;
  let errored = false;
  const reader = resp.body.getReader();
  const decoder = new TextDecoder();
  let buf = '';
  for (;;) {
    const { done: finished, value } = await reader.read();
    if (finished) break;
    buf += decoder.decode(value, { stream: true });
    let idx: number;
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const chunk = buf.slice(0, idx);
      buf = buf.slice(idx + 2);
      const { event, data } = parseSseChunk(chunk);
      if (data === null) continue;
      let p: any;
      try {
        p = JSON.parse(data);
      } catch {
        continue; // 非法负载忽略，不打断整条流
      }
      if (event === 'start') handlers.onStart?.(p);
      else if (event === 'delta') handlers.onDelta?.(p.text ?? '');
      else if (event === 'card') { if (p.node) handlers.onCard?.(p.node); }
      else if (event === 'done') { done = true; handlers.onDone?.(p.messageId ?? null); }
      else if (event === 'error') { errored = true; handlers.onError?.(p.message || '回复生成失败。'); }
    }
  }

  // 流断了但既没 done 也没 error → 中断（半截文本保留在屏上，不重发）
  if (!done && !errored) handlers.onError?.('回复中断，请重试。');
}
```

- [ ] **Step 4: `api.ts` 增加 `stream` 方法**

在 `createApi` 返回对象里 `send` 之后加入：

```ts
    /** 流式对话；404 抛 SessionExpiredError（同 send，供上层清会话后重开）。 */
    async stream(
      message: string,
      sessionId: string | null,
      handlers: ChatStreamHandlers,
      signal?: AbortSignal,
    ): Promise<void> {
      const body = sessionId ? { message, sessionId } : { message };
      try {
        await postSse(`${cfg.apiBase}/open/v1/chat/stream`, headers(), body, handlers, signal);
      } catch (e) {
        if (e instanceof Error && e.name === 'SessionExpiredError') throw new SessionExpiredError();
        throw e;
      }
    },
```

补 import：`import { postSse, type ChatStreamHandlers } from './chatStream';`。

- [ ] **Step 5: 运行测试确认通过**

Run: `cd widget && pnpm test`
Expected: 全绿（既有 46 个 + 新增 5 个）。

- [ ] **Step 6: 提交**

```bash
git add widget/src/chatStream.ts widget/src/chatStream.test.ts widget/src/api.ts
git commit -m "feat(widget): 对话流专用一次性 POST SSE 客户端"
```

---

### Task 11: widget 流式渲染接线

**Files:**
- Modify: `widget/src/ui.ts`（新增 `beginBotMessage`，抽出 `attachFeedback`）
- Modify: `widget/src/main.ts`（`handleSend` 改流式）
- Modify: `widget/src/ui.test.ts`（新增流式渲染用例）
- Modify: `backend/src/main/resources/static/widget.js`（构建产物，重新生成）

**Interfaces:**
- Consumes: `api.stream`（Task 10）、既有 `renderNode`（`widget/src/render.ts`）
- Produces: `WidgetUi.beginBotMessage(): StreamingBotMessage`，其中 `StreamingBotMessage = { appendText(text: string): void; appendCard(node: UiNode): void; finish(feedback?: BotFeedback): void }`

- [ ] **Step 1: 写失败的测试**

在 `widget/src/ui.test.ts` 追加：

```ts
  it('流式消息按序渲染文本与卡片，卡片后另起文本块', () => {
    const ui = mountUi(brand, { onSend: () => {}, onHandover: () => {} });
    const bot = ui.beginBotMessage();

    bot.appendText('查到订单：');
    bot.appendCard({ type: 'text', props: { value: '订单号 A1' } });
    bot.appendText('还需要什么？');
    bot.finish();

    const row = document.querySelector('div')!.shadowRoot!.querySelector('.qy-msg.bot .qy-text')!;
    expect(row.children.length).toBe(3);          // 文本块 + 卡片 + 新文本块
    expect(row.textContent).toContain('查到订单：');
    expect(row.textContent).toContain('订单号 A1');
    expect(row.textContent).toContain('还需要什么？');
  });

  it('流式文本全程 textContent，HTML 不被解析（XSS）', () => {
    const ui = mountUi(brand, { onSend: () => {}, onHandover: () => {} });
    const bot = ui.beginBotMessage();

    bot.appendText('<img src=x onerror=alert(1)>');
    bot.finish();

    const root = document.querySelector('div')!.shadowRoot!;
    expect(root.querySelector('img')).toBeNull();
    expect(root.querySelector('.qy-msg.bot .qy-text')!.textContent)
      .toContain('<img src=x onerror=alert(1)>');
  });

  it('多次 appendText 累加进同一个文本块', () => {
    const ui = mountUi(brand, { onSend: () => {}, onHandover: () => {} });
    const bot = ui.beginBotMessage();

    bot.appendText('你');
    bot.appendText('好');
    bot.finish();

    const container = document.querySelector('div')!.shadowRoot!
      .querySelector('.qy-msg.bot .qy-text')!;
    expect(container.children.length).toBe(1);
    expect(container.textContent).toBe('你好');
  });
```

> 实现者：`brand` 与 DOM 清理请照抄该文件既有用例的 `beforeEach`/`afterEach` 写法。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd widget && pnpm test`
Expected: FAIL —— `ui.beginBotMessage is not a function`。

- [ ] **Step 3: 实现 `beginBotMessage`**

在 `widget/src/ui.ts` 中，先把 `addBotMessage` 里的反馈按钮逻辑抽成复用函数（放在 `appendUiMsg` 之后）：

```ts
  /** 给消息行挂上赞/踩按钮（addBotMessage 与流式 finish 共用）。 */
  function attachFeedback(row: HTMLDivElement, feedback: BotFeedback) {
    // 竖排：气泡在上、反馈按钮在下方左对齐；不影响无反馈消息的横排布局
    row.classList.add('has-feedback');
    const bar = document.createElement('div');
    bar.className = 'qy-feedback';
    const up = document.createElement('button');
    up.type = 'button';
    up.textContent = '👍';
    up.setAttribute('aria-label', '有帮助');
    const down = document.createElement('button');
    down.type = 'button';
    down.textContent = '👎';
    down.setAttribute('aria-label', '没帮助');
    const pick = (value: 1 | -1, chosen: HTMLButtonElement) => {
      feedback.onFeedback(value);
      up.disabled = true;
      down.disabled = true;
      chosen.classList.add('active');
    };
    up.addEventListener('click', () => pick(1, up));
    down.addEventListener('click', () => pick(-1, down));
    bar.appendChild(up);
    bar.appendChild(down);
    row.appendChild(bar);
    msgs.scrollTop = msgs.scrollHeight;
  }
```

`addBotMessage` 中原地替换那段逻辑为 `if (feedback) attachFeedback(row, feedback);`。

新增流式句柄：

```ts
  /**
   * 开始一条流式 AI 消息，返回增量渲染句柄。
   * 文本全程 textContent（绝不 innerHTML）；卡片复用 renderNode（内含 safeUrl 白名单与结构守卫）。
   */
  function beginBotMessage(): StreamingBotMessage {
    const row = document.createElement('div');
    row.className = 'qy-msg bot';
    const container = document.createElement('div');
    container.className = 'qy-text';
    row.appendChild(container);
    msgs.appendChild(row);
    msgs.scrollTop = msgs.scrollHeight;

    // 当前文本块；插入卡片后置空，使卡片之后的文本另起一块（与后端 uiParts 的分块语义一致）
    let textEl: HTMLDivElement | null = null;
    const onAction = handlers.onAction ?? handlers.onSend;

    return {
      appendText(text: string) {
        if (!text) return;
        if (!textEl) {
          textEl = document.createElement('div');
          textEl.className = 'qy-node-text';
          container.appendChild(textEl);
        }
        textEl.textContent = (textEl.textContent ?? '') + text;
        msgs.scrollTop = msgs.scrollHeight;
      },
      appendCard(node: UiNode) {
        const el = renderNode(node, onAction);
        if (el) container.appendChild(el);
        textEl = null;
        msgs.scrollTop = msgs.scrollHeight;
      },
      finish(feedback?: BotFeedback) {
        if (feedback) attachFeedback(row, feedback);
      },
    };
  }
```

在 `WidgetUi` 接口与 return 对象里加上 `beginBotMessage`，并新增导出类型：

```ts
export interface StreamingBotMessage {
  appendText: (text: string) => void;
  appendCard: (node: UiNode) => void;
  finish: (feedback?: BotFeedback) => void;
}
```

`import type { WidgetBrand, UiPart, UiNode } from './api';`（补 `UiNode`）。

- [ ] **Step 4: 改 `main.ts` 的 `handleSend`**

```ts
  async function handleSend(message: string): Promise<void> {
    ui.addUserMessage(message);
    ui.setInputEnabled(false);
    ui.setTyping(true);

    let bot: StreamingBotMessage | null = null;
    // 首个正文事件到达时才撤「输入中…」并建消息行，避免空气泡
    const ensureBot = (): StreamingBotMessage => {
      if (!bot) {
        ui.setTyping(false);
        bot = ui.beginBotMessage();
      }
      return bot;
    };

    try {
      await streamWithRetry(message, {
        onStart: (d) => {
          sessionId = d.sessionId;
          saveSession(cfg!.appKey, d.sessionId);
          ensureInbox(d.sessionId);   // 流首即建坐席通道，不必等整条回复结束
        },
        onDelta: (t) => ensureBot().appendText(t),
        onCard: (n) => ensureBot().appendCard(n),
        onDone: (messageId) => {
          const sid = sessionId;
          ensureBot().finish(
            messageId != null && messageId > 0 && sid
              ? { onFeedback: (v) => void api.feedback(sid, messageId, v) }
              : undefined,
          );
        },
        onError: (msg) => ensureBot().appendText(`\n${msg}`),
      });
    } catch (e) {
      if (bot) (bot as StreamingBotMessage).appendText('\n回复中断，请重试。');
      else ui.addBotMessage('抱歉，服务暂时不可用，请稍后再试。');
      console.warn('[Qingyu Widget] 发送失败', e);
    } finally {
      ui.setTyping(false);
      ui.setInputEnabled(true);
    }
  }

  // 会话失效兜底：首次 404 → 清本地会话、以新会话重发一次；用户无感。
  // 404 发生在响应头阶段（尚未开始吐字），故重试不会造成半截内容重复。
  async function streamWithRetry(message: string, handlers: ChatStreamHandlers): Promise<void> {
    try {
      await api.stream(message, sessionId, handlers);
    } catch (e) {
      if (e instanceof SessionExpiredError) {
        clearSession(cfg!.appKey);
        sessionId = null;
        await api.stream(message, null, handlers);
        return;
      }
      throw e;
    }
  }
```

删除旧的 `sendWithRetry`；补 import：`import type { StreamingBotMessage } from './ui';`、`import type { ChatStreamHandlers } from './chatStream';`。

- [ ] **Step 5: 运行 widget 测试**

Run: `cd widget && pnpm test`
Expected: 全绿。若 `main.test.ts` 中断言了旧的 `api.send` 调用，按流式接口更新断言。

- [ ] **Step 6: 重建构建产物**

Run: `cd widget && pnpm build`
Expected: `backend/src/main/resources/static/widget.js` 更新（LF 行尾）。

- [ ] **Step 7: 提交**

```bash
git add widget/src/ui.ts widget/src/main.ts widget/src/ui.test.ts widget/src/main.test.ts \
        backend/src/main/resources/static/widget.js
git commit -m "feat(widget): 主聊天改流式增量渲染（文本/卡片交替，XSS 三防延续）"
```

---

### Task 12: 全量验证与真机冒烟

**Files:**
- Modify: `CLAUDE.md`（把流式冒烟清单并入既有「必须手工验证」小节）

**Interfaces:**
- Consumes: 全部前序任务
- Produces: 无（验收任务）

- [ ] **Step 1: 后端全量**

Run: `cd backend && mvn verify`（需 Docker Desktop）
Expected: 全部单测 + IT 绿。

- [ ] **Step 2: 前端与 widget 全量**

Run: `cd frontend && pnpm build && pnpm lint`，`cd widget && pnpm test && pnpm build`
Expected: 全绿；`git status` 确认 `widget.js` 已提交、工作树干净。

- [ ] **Step 3: 起环境做真机冒烟**

```bash
cd docker && docker compose up -d
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev   # 需 DASHSCOPE_API_KEY
cd frontend && pnpm dev
```

逐条验证（**自动化测试永远覆盖不到这些**）：

1. **逐字出现**：管理端发一条消息，回复逐字增量出现而非一次性刷出。
2. **token 计量非零**：`select token_count from sys_api_usage order by id desc limit 1;` —— 走 widget 发一条（管理端不计量）。`streamUsage` 失效是静默的，记 0 不抛。
3. **卡片增量渲染**：问一个会触发卡片的问题，确认围栏闭合瞬间整张卡片出现，且卡片前后的文本位置正确。
4. **知识命中 + 转人工建议**：管理端右侧命中面板有内容；低置信问题触发转人工提示条。
5. **工具调用在流式下执行**（本次最大未知数）：给应用挂一个 HTTP 工具，问一个需要调用它的问题，确认工具被调用且回复正确。
6. **历史一致**：刷新页面，历史消息的文本与卡片顺序与流式时一致（验证落库 uiSpec）。
7. **停止生成**：管理端点停止，半截回复留在屏上；刷新后消失（验证 cancel 不落库）。
8. **widget 端**：在 `widget/index.html` 里跑一遍 1/3/6，并确认转人工后坐席消息仍能实时到达（inbox 通道未被破坏）。

- [ ] **Step 4: 冒烟清单沉淀进 CLAUDE.md**

在 `CLAUDE.md` 的「升级 Spring AI / Spring Boot / 更换 embedding 模型后必须手工验证的三件事」小节后追加一小节：

```markdown
### 改动流式对话链路后必须手工验证
自动化测试同样覆盖不到（IT 里 ChatClient 被 mock，工具调用与真实 SSE 时序都看不到）：
1. **逐字增量**：回复真的逐字出现，不是一次性刷出。
2. **流式 token 计量**：走 widget 发一条 → `sys_api_usage.token_count` 非零（管理端不计量）。
3. **流式工具调用**：挂一个 HTTP 工具并提问，确认工具真的被执行（Spring AI 2.0 流式 + toolCallbacks）。
4. **卡片增量渲染 + 历史一致**：卡片在围栏闭合时整张出现；刷新后历史顺序与流式时一致。
```

- [ ] **Step 5: 提交**

```bash
git add CLAUDE.md
git commit -m "docs: 沉淀流式对话链路的手工冒烟清单"
```

---

## 自查记录

- **spec 覆盖**：§3 协议 → Task 1/6；§4.2 splitter → Task 2；§4.3 语义补齐 → Task 5；§4.1 签名收敛 + §4.4 管道 → Task 4；§4.5 控制器 → Task 6；§5.1 → Task 8/10；§5.2 → Task 9；§5.3 → Task 11；§6 测试 → 各任务 + Task 12。
- **spec 之外补的一处**：开放端 `/open/v1/chat/stream` 补 `sessionExists` 404 检查（Task 6）——spec §5.3 声称「404 在 HTTP 响应阶段判定」，但改造前流式端点并无此检查，不补的话 widget 的会话过期自动重开会失效。
- **类型一致性**：`ChatStreamEvent`/`Start`/`Delta`/`Card`/`Done`/`Error`、`persistStreamedMessages` 返回 `ChatStreamEvent`、`beginBotMessage`/`StreamingBotMessage`、`ChatStreamHandlers`（前端与 widget 同名同形状）在各任务间一致。
