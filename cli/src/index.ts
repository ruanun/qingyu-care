#!/usr/bin/env node
/**
 * 轻语 CLI 调试客户端。面向运维排查「这条回答为什么不对」。
 *
 * 它同时是 widget 协议层的第一个非浏览器消费者——core 里若还粘着浏览器假设，
 * 这里会第一个撞上。
 *
 * @author ruan
 */
// ⚠️ 相对 import 一律带 .js 扩展名（含跨目录引用 widget/src/core）：package.json 声明
// "type": "module"，tsc 编译到 ESNext 时不会给相对 import 补扩展名，Node 的 ESM loader
// 强制要求扩展名。moduleResolution: "bundler" 下 TS 支持把 .js 解析到同名 .ts 源码用于
// 类型检查，emit 时原样透传，二者刚好对上实际编译产物的目录结构（同 Task 2 遗留的说明）。
import { createInterface } from 'node:readline/promises';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { parseCliArgs, type CliOptions } from './args.js';
import { createFileStorage } from './fileStorage.js';
import { computeSessionKey } from './sessionKey.js';
import { formatCard, formatDone, formatHitDetails, formatHits, type KnowledgeHit } from './render.js';
import { createCoreApi, SessionExpiredError } from '../../widget/src/core/api.js';
import { clearSession, loadSession, saveSession } from '../../widget/src/core/session.js';
import type { ChatStreamHandlers } from '../../widget/src/core/chatStream.js';

const SESSION_FILE = join(homedir(), '.qingyu-cli', 'sessions.json');

async function main(): Promise<void> {
  let opts: CliOptions;
  try {
    opts = parseCliArgs(process.argv.slice(2));
  } catch (e) {
    console.error(`参数错误：${(e as Error).message}`);
    process.exit(1);
  }

  const storage = createFileStorage(SESSION_FILE);
  // 分键含接入点 + 身份两个维度，理由见 sessionKey.ts 顶部注释——身份不进键会导致
  // --as-visitor 续上服务端那次留下的会话，两种身份能看到的知识库范围本就不同。
  const sessionKey = computeSessionKey(opts);
  const api = createCoreApi(opts);

  let sessionId = loadSession(storage, sessionKey);

  // 两条鉴权路径能看到的知识库范围不同（RetrievalScope：INTERNAL=全见，PUBLIC=只见公开
  // 文档），不在启动时把这条摆出来，运维容易拿错视角的检索结果去归因「为什么没命中」。
  const identity = opts.apiSecret
    ? '服务端（INTERNAL 权限，可见全部文档）'
    : `访客（Origin=${opts.origin}，仅见 PUBLIC 文档）`;
  console.log(`身份：${identity}`);
  console.log(sessionId ? `续上会话 ${sessionId}` : '新会话');
  console.log('命令：/hits 展开命中详情，/new 开新会话，/exit 退出\n');

  let lastHits: KnowledgeHit[] = [];

  // 会话失效兜底：404 时清本地会话、以新会话重发一次，同 widget 侧 main.ts 的
  // streamWithRetry 语义。CLI 不支持附件上传，不必顾虑那边「附件绑在旧会话上」的细节。
  async function streamWithRetry(message: string, handlers: ChatStreamHandlers): Promise<void> {
    try {
      await api.stream(message, sessionId, handlers);
    } catch (e) {
      if (!(e instanceof SessionExpiredError)) throw e;
      clearSession(storage, sessionKey);
      sessionId = null;
      console.log('\n（会话已过期，已开新会话重试）');
      await api.stream(message, null, handlers);
    }
  }

  const rl = createInterface({ input: process.stdin, output: process.stdout });

  for (;;) {
    let raw: string;
    try {
      raw = await rl.question('> ');
    } catch (e) {
      // 两轮 question() 之间有一次真实异步网络调用（下面的 streamWithRetry）时，
      // 若 stdin 恰好在此期间耗尽（管道输入用完，或交互场景下用户按 Ctrl+D），
      // readline 会自行 close；再调用 question() 会同步抛 ERR_USE_AFTER_CLOSE。
      // 当成正常退出而非让裸调用栈甩到用户脸上——语义上等价于 /exit。
      if ((e as NodeJS.ErrnoException).code === 'ERR_USE_AFTER_CLOSE') break;
      throw e;
    }
    const line = raw.trim();
    if (!line) continue;
    if (line === '/exit') break;
    if (line === '/hits') {
      console.log(formatHitDetails(lastHits) + '\n');
      continue;
    }
    if (line === '/new') {
      clearSession(storage, sessionKey);
      sessionId = null;
      console.log('已开新会话\n');
      continue;
    }

    let suggestHandover = false;
    const handlers: ChatStreamHandlers = {
      onStart: (d) => {
        sessionId = d.sessionId;
        saveSession(storage, sessionKey, d.sessionId);
        lastHits = d.knowledgeHits;
        suggestHandover = d.suggestHandover;
        console.log(formatHits(lastHits));
      },
      onDelta: (text) => process.stdout.write(text),
      onCard: (node) => console.log('\n' + formatCard(node)),
      onDone: (messageId) => {
        console.log('\n' + formatDone({ messageId: messageId ?? undefined, suggestHandover }) + '\n');
      },
      onError: (msg) => console.error(`\n[错误] ${msg}\n`),
    };

    try {
      await streamWithRetry(line, handlers);
    } catch (e) {
      console.error(`\n[请求失败] ${(e as Error).message}\n`);
    }
  }

  rl.close();
}

void main();
