import { parseArgs } from 'node:util';

export interface CliOptions {
  apiBase: string;
  appKey: string;
  apiSecret?: string;
  origin?: string;
}

/**
 * 解析命令行参数。
 *
 * ⚠️ 两种身份互斥且必须显式二选一：开放 API 的两条鉴权路径看到的知识库不一样
 * （服务端可见 INTERNAL、浏览器访客仅见 PUBLIC）。静默退化会让运维以为自己在用某个视角、
 * 实际却是另一个——那是这个调试工具最该避免的误导。
 *
 * @author ruan
 */
export function parseCliArgs(argv: string[], env: Record<string, string | undefined> = process.env): CliOptions {
  const { values } = parseArgs({
    args: argv,
    options: {
      'api-base': { type: 'string' },
      'app-key': { type: 'string' },
      'api-secret': { type: 'string' },
      'as-visitor': { type: 'boolean', default: false },
      origin: { type: 'string' },
    },
    allowPositionals: false,
  });

  const apiBase = values['api-base'];
  const appKey = values['app-key'];
  if (!apiBase) throw new Error('缺少 --api-base');
  if (!appKey) throw new Error('缺少 --app-key');

  const asVisitor = values['as-visitor'] === true;
  // 密钥也可以从环境变量 QINGYU_API_SECRET 读：放在命令行参数里会进 shell 历史和进程列表（ps / 任务管理器）
  // （2026-09-30 安全审查）。--as-visitor 时忽略环境变量，否则全局设过它的人就再也用不了访客身份。
  const apiSecret = values['api-secret'] ?? (asVisitor ? undefined : env.QINGYU_API_SECRET);
  const origin = values.origin;

  if (asVisitor && apiSecret) {
    throw new Error('--as-visitor 与 --api-secret 不能同时使用：访客路径不校验密钥，同时给出会让你分不清实际用的是哪种身份');
  }
  if (asVisitor) {
    if (!origin) throw new Error('--as-visitor 需要 --origin（且必须是该应用 allowedOrigins 白名单里的域名，否则后端 403「来源域名未授权」）');
    return { apiBase, appKey, origin };
  }
  if (!apiSecret) {
    throw new Error('需要 --api-secret 或环境变量 QINGYU_API_SECRET（服务端身份），或用 --as-visitor --origin <白名单域名> 切到访客身份');
  }
  return { apiBase, appKey, apiSecret };
}
