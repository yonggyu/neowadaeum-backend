# ─────────────────────────────────────────────────────────────
# 애플리케이션 이미지 (B-63, S-2)
#
# **시크릿을 담지 않는다.** 이 이미지는 레지스트리에 남고 어디로든 복사된다 — 한 번 들어간 값은
# 나중에 지워도 레이어에 남는다. 그래서 설정은 `application.yml.template` 의 플레이스홀더만
# 담고, 실제 값은 **런타임 환경변수**로 온다. 값이 없으면 부팅이 실패한다 (§7.3).
#
# **`.dockerignore` 가 1차 방어선이다.** 여기서 `COPY . .` 를 쓰더라도 .env 와 키는 컨텍스트에
# 들어오지 않는다.
# ─────────────────────────────────────────────────────────────

# ── 빌드 ─────────────────────────────────────────────────────
# 다이제스트로 고정한다. 이동 가능한 태그를 쓰지 않는 관례(B-04-1)를 베이스 이미지에도 적용한다.
FROM eclipse-temurin:21-jdk@sha256:85f00967bcc624fc19fa9c2cf124ea426a5363898e267141726f31f358c2e14b AS build
WORKDIR /workspace

# 래퍼와 빌드 스크립트를 먼저 넣는다 — 의존성 해석 결과가 소스 변경마다 버려지지 않게 한다.
COPY gradlew ./
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src src
# **테스트를 여기서 돌리지 않는다.** 테스트는 CI 의 일이고(B-04), 이미지 빌드가 그것을 다시
# 하면 같은 검증이 두 곳에 생겨 어느 쪽이 진실인지가 매번 문제가 된다.
RUN ./gradlew --no-daemon bootJar -x test

# ── 실행 ─────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre@sha256:7a65df4b22d2de92d4e04056e884f3b9122d70b21e2847fd66084278bd0ce037 AS runtime

# **root 로 돌지 않는다.** 컨테이너가 뚫렸을 때 호스트로 넘어가는 거리를 늘린다.
RUN useradd --system --uid 10001 --create-home app
USER app
WORKDIR /app

# 설정은 **템플릿 그대로** 들어간다 — 플레이스홀더뿐이며 값은 하나도 없다.
# `/app/config/` 는 Spring 이 기본으로 찾는 자리다 (WORKDIR 아래 `config/`) — 경로를 인자로
# 넘기지 않는 이유가 이것이다. 인자로 넘기면 실행 명령과 이미지 구조가 서로를 알아야 한다.
COPY --from=build --chown=app:app /workspace/src/main/resources/application.yml.template \
     /app/config/application.yml
COPY --from=build --chown=app:app /workspace/build/libs/*.jar /app/app.jar

# 컨테이너에 메모리 상한이 걸려 있으면 JVM 이 그것을 본다. 상한을 무시하면 OOMKill 이
# 애플리케이션 오류처럼 보인다.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

# HEALTHCHECK 를 이미지에 넣지 않는다 — 오케스트레이터가 프로브를 갖고 있고(§B-48 이 켠
# readiness/liveness), 두 곳에 두면 어느 판정이 재시작을 부르는지가 흐려진다.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
