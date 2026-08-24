#!/usr/bin/env node
/**
 * 轻语 CLI 调试客户端入口。本任务只做到「能编译、能运行、能用上 core」，
 * 参数解析与交互循环在后续任务补齐。
 *
 * @author ruan
 */
// ⚠️ 必须带 .js 扩展名：package.json 声明 "type": "module"，tsc 编译到 ESNext 时
// 不会给相对 import 补扩展名，Node 的 ESM loader 强制要求扩展名，否则编译产物用
// `node` 直接跑会报 ERR_MODULE_NOT_FOUND（已实测）。moduleResolution: "bundler" 下
// TS 支持把 .js 解析到同名 .ts 源码用于类型检查，emit 时原样透传，二者刚好对上
// 实际编译产物的目录结构（详见 cli/README 或任务报告）。
import { safeUrl } from '../../widget/src/core/safeUrl.js';

const url = process.argv[2] ?? 'https://example.com';
console.log(`safeUrl(${url}) = ${safeUrl(url)}`);
