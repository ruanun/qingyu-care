import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import type { WidgetStorage } from '../../widget/src/core/platform';

/**
 * `WidgetStorage` 的文件实现（widget 侧对应的是 localStorage）。
 *
 * 整份文件是一个扁平的 key→value JSON，core 只收发字符串，序列化在 core 内部完成。
 * 读失败一律降级为「没有数据」而不是抛——与 widget 侧「隐私模式下 localStorage 抛异常时
 * 降级为无会话」保持同一姿态。
 *
 * @author ruan
 */
export function createFileStorage(filePath: string): WidgetStorage {
  const readAll = (): Record<string, string> => {
    try {
      return JSON.parse(readFileSync(filePath, 'utf8')) as Record<string, string>;
    } catch {
      return {};
    }
  };
  const writeAll = (data: Record<string, string>): void => {
    mkdirSync(dirname(filePath), { recursive: true });
    writeFileSync(filePath, JSON.stringify(data, null, 2), 'utf8');
  };

  return {
    get: (key) => readAll()[key] ?? null,
    set: (key, value) => {
      const data = readAll();
      data[key] = value;
      writeAll(data);
    },
    remove: (key) => {
      const data = readAll();
      delete data[key];
      writeAll(data);
    },
  };
}
