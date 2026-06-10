# syntax=docker/dockerfile:1
# Custos 一键镜像：多阶段构建——Maven 打包整个 reactor，运行 app 模块的可执行 jar。
# --mount=type=cache 持久化 ~/.m2，重复构建不再全量下依赖（BuildKit，Docker Desktop 默认开启）。
FROM maven:3.9-eclipse-temurin-21 AS build
# 默认走 Aliyun 镜像加速国内构建；海外构建可 --build-arg MAVEN_MIRROR=https://repo.maven.apache.org/maven2
ARG MAVEN_MIRROR=https://maven.aliyun.com/repository/public
RUN printf '<settings><mirrors><mirror><id>mirror</id><mirrorOf>central</mirrorOf><url>%s</url></mirror></mirrors></settings>' "$MAVEN_MIRROR" > /tmp/settings.xml
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -q -s /tmp/settings.xml -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/app/target/custos-app-0.1.0-SNAPSHOT-exec.jar /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
