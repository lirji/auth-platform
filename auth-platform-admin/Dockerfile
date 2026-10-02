# 从仓库根目录构建，确保治理库等 reactor 依赖来自当前源码而非宿主旧 JAR。
# docker build -f auth-platform-admin/Dockerfile -t auth-platform/governance-admin:local .
FROM eclipse-temurin:21-jdk@sha256:1f79c73404fb0cccf9a3459eda22892f368d994b1028d6fb1ae871c1f49749a6 AS build
WORKDIR /workspace
COPY . .
# 缓存仅保存下载依赖；forceCreation 避免增量打包复用曾被 Boot 重打包的旧包。
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl auth-platform-admin -am package -DskipTests -Dmaven.jar.forceCreation=true

FROM eclipse-temurin:21-jre@sha256:a80c51f2d09a3e7e00d521f1c817bbceb6b3be94109b4a784d46078099882dda
WORKDIR /app
COPY --from=build /workspace/auth-platform-admin/target/auth-platform-admin-0.1.0-SNAPSHOT.jar /app/admin.jar
EXPOSE 8201
CMD ["java", "-Xmx384m", "-jar", "/app/admin.jar"]
