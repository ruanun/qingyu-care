# 品牌资产

轻语（QingYu Care）的 logo 与色值。**这里是唯一的品牌资产入口**，其余位置都是副本。

## 资产

| 文件 | 内容 | 用在哪 |
|:---|:---|:---|
| `logo-mark.svg` | 纯图标，靛蓝渐变 | **基准**。favicon、管理端、widget 的形状都以它为准 |
| `logo-lockup.svg` | 图标 + 字标 + 标语 | README banner；对外场合（幻灯片、物料）用这份 |

图形是**对话气泡（尖角左下）+「轻」字骨架**：左三笔＝车字旁（竖 + 两横），右三笔＝
「圣」简化成「工」（横竖横）。

实心气泡 + `mask` 挖空笔画，而不是描边。收益是**深浅背景通吃**：换个 `fill` 就能用，
笔画处透出的是背景本身，不必知道背景是什么颜色——widget 悬浮球正是靠这点让笔画透出
每个租户自配的 `themeColor`，无需为每种主题色单独出图。

## 色值

| 用途 | 值 |
|:---|:---|
| 主色（渐变起点） | `#6366f1` indigo-500 |
| 副色（渐变终点） | `#8b5cf6` violet-500 |
| 字标正文 | `#6366f1` |
| 字标副标题 | `#64748b` slate-500 |
| 单色变体 | `currentColor`，由父元素文字色驱动 |

⚠️ **字标的两个文字色刻意避开管理端那个近黑的 `--primary`**：GitHub 暗色主题背景是
`#0d1117`，深色字几乎不可见。靛蓝与 slate-500 在白底和暗底上都达标，因此一份文件通吃
两种主题，不必做 `<picture>` 双套资产。

⚠️ **管理端的 `--primary` 没有改成靛蓝**。logo 自带品牌色，全站按钮/选中态/focus ring
仍是原来的近黑 slate。改 `--primary` 是主题改版，会让 `docs/images/` 的 15 张截图全部
失真，属另一件事。

## 五处副本

路径数据在下面五个位置各有一份。三个独立构建目标（frontend / widget / 文档）没有共享
模块的通道，副本无法消除，但**必须保持同源**：

| 位置 | 形态 | 约束 |
|:---|:---|:---|
| `brand/logo-mark.svg` | SVG | 基准 |
| `frontend/public/logo.svg` | SVG | 与基准**逐字节相同**（Vite 要求 favicon 物理落在 `publicDir` 内） |
| `frontend/src/components/Logo.tsx` | React 内联 | 路径数据同源 |
| `widget/src/logo.ts` | SVG 字符串常量 | 路径数据同源 |
| `brand/logo-lockup.svg` | SVG | 路径数据同源 |

`frontend/scripts/brandAssetSync.test.ts` 守着这五处：路径数据集合两两比对、色值集合
比对、基准与 favicon 字节比对。改一处漏其它即红。

## 改 logo 的步骤

1. 改 `logo-mark.svg`（基准）。
2. 同步另外四处。favicon 直接 `cp brand/logo-mark.svg frontend/public/logo.svg`；
   其余三处手工对齐路径数据。
3. `cd frontend && pnpm test` —— 同步哨兵在这里，没同步全会红。
4. ⚠️ **`cd widget && pnpm build` 并提交 `backend/src/main/resources/static/widget.js`**。
   `widget.js` 是提交进仓库的构建产物；只改 `widget/src/logo.ts` 不重新 build，线上跑
   的还是旧图标，而**没有任何日志会提示**。`widget` 的 `buildFreshness.test.ts` 会拦住
   这条，别跳过 `cd widget && pnpm test`。
5. 目视复核：logo 在 **16px** 下最容易糊，缩到 favicon 尺寸看一眼再收工。

## 两条别踩的

⚠️ **右侧那一竖不能删**。最初右侧只画两横，渲染出来整个图形读作 **「+=」**——一个客服
产品的 logo 里嵌进编程运算符是意外语义。中间那一竖是专门用来打断等号读法的。这个问题
代码层面完全正常，只有真的渲染出来才看得见。

⚠️ **`logo-lockup.svg` 的字标用系统字体渲染**，跨平台字形会有差异（SVG 的 `text` 取阅读
者本机字体）。这是 banner 的常规取舍；要完全一致得把字转成路径，中文字形无法手工编写。

## 为什么不做「单一真源 + 构建期生成」

评估过，判为不划算：`frontend` 与 `widget` 各自是独立的 pnpm workspace，真要共享一份
文件得付三处代价——`Logo.tsx` 要靠字符串替换做 id 唯一化与变体切换（比现在内联脆）、
`widget` 的源码指纹得扩到仓库外的文件（不扩就是「改 logo 不触发 widget 重建」的静默
陈旧，比漂移严重得多）、dev server 还要放开 `server.fs.allow`。为消掉约 7 行几乎不变的
路径数据引入三处新耦合，不值。**改用哨兵把漂移从「靠人记得」变成「测试拦住」。**
