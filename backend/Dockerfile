# =============================
# 1단계: 빌드용 이미지 (Gradle + JDK)
# =============================
FROM eclipse-temurin:21-jdk AS builder

# 작업 디렉토리 설정
WORKDIR /app

# Gradle Wrapper 및 설정 파일 먼저 복사 (캐시 활용)
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle* settings.gradle* ./

# 나머지 소스 코드 복사
COPY . .

# Gradle wrapper 실행 권한 부여
RUN chmod +x gradlew

# 테스트는 건너뛰고(Spring Boot JAR 빌드만)
RUN ./gradlew clean bootJar -x test


# =============================
# 2단계: 실제 서비스용 이미지 (JRE)
# =============================
FROM eclipse-temurin:21-jre

# 프로필은 운영용(prod)으로
ENV SPRING_PROFILES_ACTIVE=prod

# 작업 디렉토리
WORKDIR /app

# 1단계에서 빌드한 JAR 파일 복사
# (build/libs 안에 있는 .jar 하나를 app.jar로 복사)
COPY --from=builder /app/build/libs/*.jar app.jar

# 컨테이너가 외부에 개방할 포트
EXPOSE 8080

# =============================
# HEALTHCHECK
# =============================
# ⚠️ 이것이 컨테이너를 재시작시키지 않는다.
#
#    `--restart unless-stopped` 는 **프로세스 종료**에만 반응한다.
#    HEALTHCHECK 가 unhealthy 를 보고해도 Docker 는 컨테이너를 다시 띄우지 않는다.
#    (그렇게 동작하는 것은 Swarm/Kubernetes 이고, 여기는 단일 호스트의 docker run 이다)
#
#    그래서 이 값의 용도는 두 가지다.
#      1) `docker ps` 에서 상태를 눈으로 보기
#      2) 배포 스크립트가 "이 배포가 성공인가"를 판정하기
#
# readiness 를 본다. liveness 를 보면 DB 가 끊겨도 healthy 로 나와
# "요청을 받을 수 있는가"를 판정하지 못한다.
#
# start-period: 실측 기동 시간이 21.1초다(CS 12). 그 안의 실패는 세지 않는다.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
    CMD curl -sf http://127.0.0.1:8080/readyz || exit 1

# =============================
# 비루트 실행
# =============================
# 예전에는 root 로 돌았다. 컨테이너 탈출이나 애플리케이션 취약점이 곧바로
# 호스트 root 로 이어지지 않도록 권한을 낮춘다.
# 앱은 8080(비특권 포트)만 열고 파일을 쓰지 않으므로 잃는 기능이 없다.
RUN useradd --system --create-home --shell /usr/sbin/nologin nemo \
    && chown nemo:nemo /app/app.jar
USER nemo

# 컨테이너 시작 시 실행할 명령
ENTRYPOINT ["java", "-jar", "app.jar"]
