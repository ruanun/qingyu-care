# ---------- 构建阶段 ----------
# Node 与 pnpm 都对齐本地工具链（Node 24 / pnpm 11.13.0，锁文件 lockfileVersion 9.0），
# 不用 latest：corepack 拉 pnpm@latest 会装到 11.21，它依赖 Node 20 没有的内置模块，
# 在 node:20 上直接 ERR_UNKNOWN_BUILTIN_MODULE 构建失败。
FROM node:24-alpine AS builder
WORKDIR /build

# CI=true 让 pnpm 在无 TTY 环境下不去交互式确认清理 node_modules
# （否则 pnpm build 前的依赖状态检查会以 ERR_PNPM_ABORTED_REMOVE_MODULES_DIR_NO_TTY 失败）
ENV CI=true

RUN corepack enable && corepack prepare pnpm@11.13.0 --activate

# 先拷依赖清单装依赖，源码变动时这一层仍能命中缓存。
# pnpm-workspace.yaml 不能漏——它带着 allowBuilds: esbuild，缺了 pnpm 11 会以
# ERR_PNPM_IGNORED_BUILDS 直接失败（esbuild 要靠 postinstall 落平台二进制）。
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile

COPY . .
RUN pnpm build

# ---------- 运行阶段 ----------
FROM nginx:alpine

COPY --from=builder /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

# 必须写 127.0.0.1 而不是 localhost：容器内 localhost 先解析到 ::1，而 nginx
# 的 listen 80 只绑 IPv4，探测会 Connection refused——服务明明正常却恒报
# unhealthy，进而卡住 depends_on: condition: service_healthy 的编排。
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -q --spider http://127.0.0.1/ || exit 1
