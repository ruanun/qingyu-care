import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

// ⚠️ 必须显式 cleanup。RTL 只在 `afterEach` 是全局函数时才自动注册清理，
// 而本项目故意关着 vitest 的 globals——漏掉这行的症状是「单个测试跑绿、
// 整套一起跑红」，因为上一个测试的 DOM 还挂在 document 上，
// screen 的查询会撞到重复元素。定位这个症状很费时间，所以在基建里钉死。
afterEach(cleanup);
