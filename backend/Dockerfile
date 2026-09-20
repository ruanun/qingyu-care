# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /build

# 先只拷 pom 预拉依赖：源码变动时这一层仍能命中缓存，重建快得多
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src

# 跳过测试：镜像构建环境里没有 Docker，集成测试（Testcontainers）跑不起来。
# 测试由本地 mvn verify 或 CI 把关，不在打包阶段重复。
RUN mvn -B clean package -DskipTests

# ---------- 运行阶段 ----------
FROM eclipse-temurin:25-jre
WORKDIR /app

# 非 root 运行
RUN groupadd -r qingyu && useradd -r -g qingyu qingyu

COPY --from=builder /build/target/qingyu-care-*.jar app.jar
RUN chown -R qingyu:qingyu /app
USER qingyu

EXPOSE 8080

# start-period 给足 90s：首次启动要建表（ddl-auto: update）并播种平台默认配置
#
# ⚠️ 刻意不用 curl：`eclipse-temurin:25-jre` 不自带 curl（17-jre 自带），装它就得
# `apt-get`，而那让镜像构建硬依赖 Ubuntu 源可达——国内网络下大概率 502 直接构建失败。
# 这里改用基础镜像自带的 bash 手搓 HTTP：`/dev/tcp` 是 bash 内建的伪设备，不是外部命令。
# 用 HTTP/1.0：HTTP/1.1 规定必须带 Host 头，不带是不合规的；1.0 无此要求，且服务端响应完
# 即关连接，不留半开连接。
# ⚠️ 这里**不存在** keep-alive 挂死的问题——`head -1` 读到第一行就退出，不等 EOF。实测
# 1.0 与 1.1 都是百毫秒级返回。若将来把读取改成「读完整个响应体」（`cat <&3`），
# HTTP/1.1 下就会一直读不到 EOF、挂满 `--timeout` 才判失败，那时这个版本号才变成承重的。
# 判据只看 HTTP 状态行是不是 200——这是**忠实复刻原来 `curl -fsS` 的语义**，不多不少。
# actuator 的契约就是「整体 DOWN / OUT_OF_SERVICE → 503，UP → 200」，实测停掉 postgres
# 后该端点确实返回 `HTTP/1.1 503`、容器 15 秒转 unhealthy。
#
# ⚠️ **不要再加「grep 响应体里的 status」当第二判据**，这条路试过且是错的：
# `show-details` 一旦从 when-authorized 改成 always（排障常规操作），响应体变成
# `{"components":{"db":{...,"status":"UP"},...},"status":"..."}`——**顶层 status 排在
# components 后面**，`grep -o ... | head -1` 取到的是 db 组件的状态而非顶层。实测确认。
# JSON 不是行结构，字段顺序随配置变，用 grep 在 shell 里提顶层字段没有可靠做法；而状态码
# 那一半本来就已经覆盖了所有整体非 UP 的情形，加上去只会凭空引入误判。
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 || exit 1; printf 'GET /api/actuator/health HTTP/1.0\\r\\n\\r\\n' >&3; head -1 <&3 | grep -q ' 200 '"]

# MaxRAMPercentage 让 JVM 按容器内存限额自适配堆大小，而不是按宿主机物理内存
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
