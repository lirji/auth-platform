# 先由根目录 Maven reactor 构建；镜像上下文仅允许打包后的可执行 JAR。
FROM eclipse-temurin:21-jre@sha256:a80c51f2d09a3e7e00d521f1c817bbceb6b3be94109b4a784d46078099882dda
WORKDIR /app
COPY target/auth-platform-admin-0.1.0-SNAPSHOT.jar /app/admin.jar
EXPOSE 8201
CMD ["java", "-Xmx384m", "-jar", "/app/admin.jar"]
