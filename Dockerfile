# 运行时镜像：jar 在本机构建后上传服务器（2核4G 服务器不做前端/Maven 构建，省内存省时间）。
# 构建链路：cd frontend && npm install && npm run build && cd .. && ./mvnw clean package
# 产物：target/Agentdemo007-0.0.1-SNAPSHOT.jar（含前端静态资源，单 jar 即整站）
FROM eclipse-temurin:17-jre

WORKDIR /app

# healthcheck 需要 curl
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY target/Agentdemo007-*.jar app.jar

# JVM 参数经 compose 注入 JAVA_OPTS（2核4G 口径：-Xmx800m + Metaspace 256m）
ENV JAVA_OPTS=""

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
