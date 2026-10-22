/**
 * 平台差异的注入点。core 不直接碰任何平台 API，由各端调用方提供实现。
 *
 * ⚠️ storage 只收发**字符串**：序列化由 core 内部负责，这样各端实现不必关心存的是什么结构，
 * 也避免「某端存对象、某端存字符串」的分叉。
 *
 * ⚠️ **依赖方向单向：core 内任何文件都不得 import widget/src/ 下 core 之外的东西**
 * （即不得 `from '../xxx'`，只能 `from './xxx'`）。原因两条：
 * 1. CLI（`cli/`）编译时把 `widget/src/core/**` 一起纳入 `tsconfig.json` 的 `include`——
 *    反向 import 一条，就会把整个 widget UI 层（`ui.ts`/`render.ts`/`styles.ts`/…，里面全是
 *    `document`/`HTMLElement` 等 DOM API）连带编译进 CLI 的 `dist`。这件事**不保证会报错**
 *    （CLI 当前 tsconfig 没显式收窄 `lib`，TS 默认仍带 DOM lib，能编译过），但会让本不需要
 *    渲染代码的 CLI 产物体积膨胀、且一旦以后收紧 `lib` 就会静默炸掉——已用
 *    `tsc --listFilesOnly` 实测复现过整个 widget 层被拖进 `dist`。
 * 2. 反向依赖本身就让「把协议逻辑抽成 core」这件事没有达成——widget 专属的类型（如
 *    `WidgetAttachment` 这种纯数据 DTO）该下沉到 core 定义，widget 侧文件重导出即可；
 *    真正耦合 DOM 渲染的类型（如 `WidgetUi`）不该被 core 引用，若某个 core 模块看起来需要
 *    它，说明该模块该在 core 里定义一个只含自己用到的方法的最小接口（结构类型，widget 的
 *    实现自然满足，不需要显式 implements），而不是原样导入整个 UI 层类型。
 *
 * @author ruan
 */
export interface WidgetStorage {
  get(key: string): string | null;
  set(key: string, value: string): void;
  remove(key: string): void;
}
