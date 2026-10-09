# 运行时镜像：jar 在本机构建后上传服务器（2核4G 服务器不做前端/Maven 构建，省内存省时间）。
# 构建链路：cd frontend && npm install && npm run build && cd .. && ./mvnw clean package
# 产物：target/Agentdemo007-0.0.1-SNAPSHOT.jar（含前端静态资源，单 jar 即整站）
#
# 构建期零外网依赖：全文件无 RUN 层。2026-10-08 实测 CN 网络连 RUN apt 都不可行
# （archive.ubuntu.com 超时 404；aliyun 源 universe 索引同样连接失败），故彻底消灭 apt。
# 应用运行时不需要任何系统包；compose healthcheck 用 bash 内建 /dev/tcp 发 HTTP 探测
# （见 docker-compose.prod.yml）——不押注 curl（当前 jammy 底座虽自带，但上游换底座时
# 自带什么会漂，17-jre 漂到 resolute 引发本次事故）；bash 是 Ubuntu required/essential
# 级包，任何 Ubuntu 底座必有，是比 curl 强得多的依赖锚点。
# 基础镜像钉 -jammy（LTS）：浮动 tag 会被上游换底座（17-jre 曾漂到 resolute）；
# 已无 RUN 层，即使 tag 再漂移也只是换 COPY 底座，构建不再依赖外网。
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

COPY target/Agentdemo007-*.jar app.jar

# JVM 参数经 compose 注入 JAVA_OPTS（2核4G 口径：-Xmx800m + Metaspace 256m）
ENV JAVA_OPTS=""

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
