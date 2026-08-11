# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

# 先只拷 pom 预拉依赖：源码变动时这一层仍能命中缓存，重建快得多
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src

# 跳过测试：镜像构建环境里没有 Docker，集成测试（Testcontainers）跑不起来。
# 测试由本地 mvn verify 或 CI 把关，不在打包阶段重复。
RUN mvn -B clean package -DskipTests

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app

# 健康检查要用 curl，基础镜像不带
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 非 root 运行
RUN groupadd -r qingyu && useradd -r -g qingyu qingyu

COPY --from=builder /build/target/qingyu-care-*.jar app.jar
RUN chown -R qingyu:qingyu /app
USER qingyu

EXPOSE 8080

# start-period 给足 90s：首次启动要建表（ddl-auto: update）并播种平台默认配置
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD curl -fsS http://localhost:8080/api/actuator/health || exit 1

# MaxRAMPercentage 让 JVM 按容器内存限额自适配堆大小，而不是按宿主机物理内存
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
