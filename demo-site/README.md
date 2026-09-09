# 云舟商城 · Widget 演示站点

模拟一个第三方电商网站，用一行 `<script>` 嵌入轻语 Widget，用来演示「客户把 Widget 接进自己网站后是什么样」。

配套 [`docs/images/15-widget-chat.png`](../docs/images/15-widget-chat.png)（README 里那张 Widget 实机图）就是在这个页面上拍的。前 14 张截图都在管理后台里、起 `dev,demo` profile 就能重拍，只有第 15 张需要一个外部宿主页——本目录补的就是它。

零依赖、零构建：一个 HTML 加一个 CSS，商品配图是纯 CSS 灰块占位。

## 起法

先按 [部署文档 3.6 节](../docs/部署文档.md) 起好带 `demo` profile 的后端（演示数据里的「云舟商城客服」应用会被播种出来），然后：

```bash
npx serve demo-site -l 3000
```

打开 <http://localhost:3000>，右下角出现绿色气泡即接入成功。

## ⚠️ 必须起在 3000 端口

演示应用的 `allowedOrigins` 被 `DemoDataSeeder` 播种成 `http://localhost:5173,http://localhost:3000`，而 Origin 白名单是**协议 + 域名 + 端口完全一致**匹配。起在 8000、5500 等其他端口时开放 API 会以 403 拒绝，**症状是气泡能点开、发消息却毫无反应**——不会有任何弹窗或提示，只能在浏览器控制台看到 403。

要换端口或换域名，用 `demo_admin` / `demo123456` 登录管理后台 →「应用管理」→ 编辑「云舟商城客服」→ 把新地址加进「允许来源」。

## ⚠️ 后端地址按部署方式二选一

`index.html` 末尾那行 script 默认指向本地 dev 后端：

```html
<script src="http://localhost:8080/api/widget.js?appKey=demo-cloudboat-shop" defer></script>
```

| 部署方式 | 地址 |
|---|---|
| 本地 dev（`mvn spring-boot:run`，后端直连） | `http://localhost:8080/api/widget.js` |
| 容器化（`docker-compose.prod.yml`，前端 nginx 反代 `/api`） | `http://localhost/api/widget.js` |

容器化部署时记得改这一行，否则 8080 在 prod compose 里根本没对宿主机映射，widget 加载不出来。

## 关于 appKey

`demo-cloudboat-shop` 是 `DemoDataSeeder` 里写死的演示值，appSecret 同理。**任何读过仓库的人都知道这对凭据**，所以演示环境一旦对公网开放，要么在管理后台重置密钥，要么把该应用的 `rateLimitPerMinute` 调低并接受它可被任意调用。

## 重拍第 15 张截图

1. 起 `dev,demo` profile 的后端 + 本页面（`npx serve demo-site -l 3000`）
2. 点开气泡，问一句知识库答得上的问题（截图里问的是「多少钱包邮？偏远地区呢？」）
3. 等回复完整渲染后，按 1440×900 截图，存为 `docs/images/15-widget-chat.png`

⚠️ 文件名被 README 引用着，别改名。
