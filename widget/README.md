# Qingyu Widget

一行 `<script>` 嵌入的浏览器客服挂件，复用开放 REST API。

## 开发

```bash
pnpm install
pnpm dev      # 本地调试（index.html）
pnpm test     # vitest 单测
```

## 构建

```bash
pnpm build    # 产出单文件到 ../backend/src/main/resources/static/widget.js
```

**重要**：`widget.js` 为构建产物且**提交入库**（自托管无 CDN，保证部署零依赖、`/api/widget.js` 不 404）。
修改 `src/` 后必须重新 `pnpm build` 并把更新后的 `backend/src/main/resources/static/widget.js` 一并提交，
否则线上产物会与源码不一致。CI 应校验一致性：

```bash
pnpm -C widget build && git diff --exit-code backend/src/main/resources/static/widget.js
```

## 嵌入

```html
<script src="https://<host>/api/widget.js?appKey=app_xxx&v=1"></script>
```

`appKey` 在管理后台「接入配置」页获取；需先配置该应用的「允许来源域名」。
