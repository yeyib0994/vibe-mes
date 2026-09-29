# FluxMES 后端镜像（Spring Boot 4.1.1 / JDK 21）
# 构建上下文 = apps/api-java，需先执行: bash scripts/mvn.sh -DskipTests package
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
# 版本号与 pom.xml 保持一致（target 下有历史 jar，必须显式指定，避免多文件 COPY 冲突）
COPY target/api-java-0.2.0.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
