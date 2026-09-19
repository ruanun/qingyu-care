# Qingyu CLI

面向运维排查「这条回答为什么不对」的终端调试客户端，走的是与 widget 相同的开放 API
（`/open/v1/chat/*`）。零运行时第三方依赖：参数解析用 Node 内置 `util.parseArgs`，交互用
`readline`，HTTP 用原生 `fetch`。

它同时是 `widget/src/core/`（widget 协议层）的第一个非浏览器消费者——存在的意义不只是工具
本身，而是逼出协议层里还粘着多少浏览器假设（详见 `widget/src/core/platform.ts` 顶部注释）。

## 两种身份

开放 API 是双路径鉴权，**两条路径能看到的知识库不一样**：

| 参数 | 鉴权方式 | 可见文档 |
|---|---|---|
| `--api-secret <secret>` | `X-App-Id` + `X-Api-Key` | 服务端身份，INTERNAL + PUBLIC 全见 |
| `--as-visitor --origin <域名>` | `X-App-Id` + Origin 白名单 | 浏览器访客身份，仅见 PUBLIC |

两者**互斥**，必须显式二选一——同时给出会被拒绝，缺一个会报错提示，不会静默退化成某一种。
CLI 启动时会打印当前身份，排查前先确认这一行。

⚠️ 拿错视角排查访客问题会得出「我这儿明明查得到」的误导结论：服务端身份能看到的文档集合
比访客大，用它复现不出只有访客视角才会命中不到的问题。排查「访客为什么得到这个回答」必须用
`--as-visitor`。`--origin` 必须是该应用「允许来源域名」白名单里的域名，否则后端 401。

## 安装与构建

```bash
pnpm install
pnpm build   # tsc；产物在 dist/cli/src/index.js
pnpm test    # vitest
```

⚠️ **它 import 的是 `widget/src/core` 的 TS 源码**（`tsconfig.json` 的 `include` 里带
`../widget/src/core/**/*.ts`），不是某个发布包。改了 core 的任何文件，CLI 必须重新
`pnpm build` 才会生效——`dist/` 是各自独立的编译产物，不会自动感知源码变化。

## 用法

```bash
node dist/cli/src/index.js --api-base http://localhost:8080/api --app-key <appKey> --api-secret <secret>
node dist/cli/src/index.js --api-base http://localhost:8080/api --app-key <appKey> --as-visitor --origin https://已在白名单的域名
```

进入交互循环后，输入即发、流式打印。斜杠命令：

- `/hits` 展开上一轮的检索命中详情（来源 + 相似度 + 正文，单测里 fetch 是桩的，这些数值只有
  真实请求才拿得到）
- `/new` 清本地会话，开一个新会话
- `/exit` 退出

卡片直接打印原始 `ui_spec` JSON——运维要的是它到底长什么样，不是好看的渲染；CLI 不解析、
不渲染按钮。

## 会话文件

`~/.qingyu-cli/sessions.json`，按 `apiBase + appKey` 分键，同一台机器上调试多个应用/环境时
互不覆盖，下次启动自动续上对应的会话。会话失效（404）时自动清本地记录、开新会话重发一次，
与 widget 侧同一语义。
