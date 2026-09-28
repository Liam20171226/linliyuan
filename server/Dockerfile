# 构建阶段
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package \
 && cp target/property-mgmt-server-*.jar /src/app.jar

# 运行阶段
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN mkdir -p /app/uploads
COPY --from=build /src/app.jar /app/app.jar
ENV UPLOAD_DIR=/app/uploads
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
EXPOSE 8080
# Railway 注入 PORT；未设置时默认 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dserver.port=${PORT:-8080} -jar /app/app.jar --spring.profiles.active=${SPRING_PROFILES_ACTIVE:-prod,mysql}"]
