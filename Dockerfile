FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace
COPY . .

RUN chmod +x gradlew \
    && ./gradlew bootJar --no-daemon -x test \
    && jar_file="$(find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit)" \
    && test -n "$jar_file" \
    && cp "$jar_file" /app.jar

FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin appuser \
    && mkdir -p /app/uploads/group-images \
    && chown -R appuser:appuser /app/uploads

WORKDIR /app
COPY --from=build /app.jar /app/app.jar

USER appuser
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
