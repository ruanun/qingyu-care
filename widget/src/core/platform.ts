/**
 * 平台差异的注入点。core 不直接碰任何平台 API，由各端调用方提供实现。
 *
 * ⚠️ storage 只收发**字符串**：序列化由 core 内部负责，这样各端实现不必关心存的是什么结构，
 * 也避免「某端存对象、某端存字符串」的分叉。
 *
 * @author ruan
 */
export interface WidgetStorage {
  get(key: string): string | null;
  set(key: string, value: string): void;
  remove(key: string): void;
}
