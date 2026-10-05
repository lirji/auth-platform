# 当前reactor源码构建内部判权入口，不复制宿主JAR或机密。
FROM eclipse-temurin:21-jdk@sha256:1f79c73404fb0cccf9a3459eda22892f368d994b1028d6fb1ae871c1f49749a6 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl auth-platform-server -am package -DskipTests -Dmaven.jar.forceCreation=true

FROM eclipse-temurin:21-jre@sha256:a80c51f2d09a3e7e00d521f1c817bbceb6b3be94109b4a784d46078099882dda
RUN useradd --uid 10001 --create-home authz
WORKDIR /app
COPY --from=build /workspace/auth-platform-server/target/auth-platform-server-0.1.0-SNAPSHOT.jar /app/server.jar
USER authz
EXPOSE 18545
ENTRYPOINT ["java", "-Xmx384m", "-jar", "/app/server.jar"]
