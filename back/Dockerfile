# 빌드: docker build -t tingle-back . (back/ 에서)
# 실행할 때 SPRING_PROFILES_ACTIVE 를 꼭 넘긴다. 없으면 local 프로필로 떠서 compose.yaml 로 DB 를 띄우려다 실패한다.
# 그 밖의 설정(SPRING_DATASOURCE_*, JWT_SECRET 등)도 환경변수로 받는다 (back/.env.example). 테스트·checkstyle 은 CI(Jenkins)가 돌린다.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
# 의존성 받기를 따로 두어 소스만 바뀌면 이 단계는 캐시를 쓴다
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN sh gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN sh gradlew --no-daemon -q bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
# 이미지에 있는 ubuntu(uid 1000)로 실행한다. 서버 /data/uploads 소유자와 같다.
USER ubuntu
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
