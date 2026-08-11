/**
 * 「HTTP 200 + body.code」这条全局约定的统一处理点。
 *
 * `api/client.ts` 的响应拦截器只特殊处理 401，其余状态原样把后端那整个
 * `ApiResponse`（`{code, message, data}`）resolve 给调用方——**axios 不会 reject
 * 业务异常**（后端 `BusinessException` 是 HTTP 200 + `code` 400）。所以每个调用方
 * 都必须自己判 `code`，判漏了就把后端的业务错误静默吞掉：用户点按钮毫无反应、
 * 也没有任何报错。技术债清单第 9 项记的就是这个，一度有 36 处写操作是这个形态。
 *
 * @author ruan
 */

/** 只取判 code 需要的两个字段，读操作与写操作的响应都能传进来 */
type CodedResult = { code: number; message: string };

/**
 * 业务 code 不为 200 时抛 `Error`，把控制权交给调用方**已有的** catch 块。
 *
 * 这是本仓库既定写法（`CrawlSiteList` / `IntegrationPage` / `SharedModelPage` /
 * `ConfigForm` 等都是它）：调用点写成
 *
 * ```ts
 * try {
 *     const res = await xxxService.update(...);
 *     throwIfFailed(res);
 *     // 成功分支
 * } catch (error: unknown) {
 *     toast({ variant: 'destructive', title: '更新失败', description: getErrorMessage(error) });
 * }
 * ```
 *
 * ⚠️ 不要写成 `if (res.code === 200) { 成功分支 }`——那样业务错误既不进成功分支、
 * 也不进 catch，什么都不会发生。
 */
export function throwIfFailed(res: CodedResult): void {
    if (res.code !== 200) throw new Error(res.message);
}

/**
 * 从 catch 到的未知值里取可展示的消息。
 *
 * 存在的意义不只是避免 `catch (error: any)`（那 47 处 `any` 是 lint 基线里最大的
 * 一块，见技术债第 7 项）：`catch` 参数在 TS 里是 `unknown`，直接读 `.message`
 * 编译不过，而 throw 进来的也不保证是 `Error`（第三方库 throw 字符串的有先例）。
 */
export function getErrorMessage(error: unknown): string {
    return error instanceof Error ? error.message : String(error);
}
