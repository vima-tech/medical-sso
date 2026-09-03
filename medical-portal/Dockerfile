# 本地运行镜像。start-local.sh 已先完成 Maven 构建，这里只封装已验证的 JAR，
# 避免容器内重复下载依赖，也保证 18081 与刚通过测试的产物完全一致。
FROM docker.io/library/eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --home-dir /app app
WORKDIR /app
COPY medical-portal/target/medical-portal-0.1.0.jar /app/app.jar
USER 10001
EXPOSE 18082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
