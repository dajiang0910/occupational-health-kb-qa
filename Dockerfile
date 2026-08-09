# ── 阶段 1：构建 ──
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# 缓存 Maven 依赖
COPY pom.xml ./
COPY kb-boot/pom.xml kb-boot/
COPY kb-core/pom.xml kb-core/
COPY kb-infrastructure/pom.xml kb-infrastructure/
COPY kb-api/pom.xml kb-api/
COPY kb-admin/pom.xml kb-admin/

RUN apk add --no-cache maven && \
    mvn dependency:go-offline -B -q || true

# 复制源码并构建
COPY . .
RUN mvn package -DskipTests -B -q

# ── 阶段 2：运行 ──
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# 安全：非 root 用户
RUN addgroup -S ohkb && adduser -S ohkb -G ohkb
USER ohkb

# 健康检查依赖
RUN apk add --no-cache curl

# 复制 JAR
COPY --from=builder /app/kb-boot/target/kb-boot-*.jar app.jar

# 环境变量
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseZGC -XX:+ZGenerational"
ENV PORT=8080

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:${PORT}/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
