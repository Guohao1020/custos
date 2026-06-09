# Custos 一键镜像：多阶段构建——Maven 打包整个 reactor，运行 app 模块的可执行 jar。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN mvn -q -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/app/target/custos-app-0.1.0-SNAPSHOT.jar /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
