# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace
COPY . .

RUN --mount=type=secret,id=github_actor \
    --mount=type=secret,id=github_token \
    export GITHUB_ACTOR="$(cat /run/secrets/github_actor)" \
    && export GITHUB_TOKEN="$(cat /run/secrets/github_token)" \
    && chmod +x gradlew \
    && ./gradlew bootJar --no-daemon -x test \
    && jar_file="$(find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit)" \
    && test -n "$jar_file" \
    && cp "$jar_file" /app.jar

FROM eclipse-temurin:21-jre-jammy

RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin appuser \
    && mkdir -p /app/uploads/group-images \
    && chown -R appuser:appuser /app/uploads

WORKDIR /app
COPY --from=build /app.jar /app/app.jar

USER appuser
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
