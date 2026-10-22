// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { mkdtempSync, rmSync, existsSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { createFileStorage } from './fileStorage';

let dir: string;
let file: string;

beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), 'qingyu-cli-'));
  file = join(dir, 'sessions.json');
});

afterEach(() => {
  rmSync(dir, { recursive: true, force: true });
});

describe('createFileStorage', () => {
  it('存进去能读回来', () => {
    const s = createFileStorage(file);
    s.set('k1', 'v1');
    expect(s.get('k1')).toBe('v1');
  });

  it('跨实例可读（真的落到了文件）', () => {
    createFileStorage(file).set('k1', 'v1');
    expect(createFileStorage(file).get('k1')).toBe('v1');
  });

  it('文件不存在时读返回 null 而不是抛', () => {
    expect(createFileStorage(join(dir, 'nope.json')).get('k1')).toBeNull();
  });

  it('文件内容损坏时降级为空而不是抛', () => {
    const s = createFileStorage(file);
    s.set('k1', 'v1');
    writeFileSync(file, '这不是 JSON');
    expect(createFileStorage(file).get('k1')).toBeNull();
  });

  it('remove 后读不到，其它键不受影响', () => {
    const s = createFileStorage(file);
    s.set('k1', 'v1');
    s.set('k2', 'v2');
    s.remove('k1');
    expect(s.get('k1')).toBeNull();
    expect(s.get('k2')).toBe('v2');
  });

  it('目录不存在时会自动创建', () => {
    const nested = join(dir, 'a', 'b', 'sessions.json');
    createFileStorage(nested).set('k1', 'v1');
    expect(existsSync(nested)).toBe(true);
  });
});
