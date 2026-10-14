FROM eclipse-temurin:21.0.12.1_1-jdk-noble AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY Proto Proto
COPY Server Server
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon :Server:installDist

FROM eclipse-temurin:21.0.12.1_1-jre-noble
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home app
WORKDIR /app
COPY --from=build /workspace/Server/build/install/Server /app
RUN mkdir /app/logs && chown app:app /app/logs
ENV LOG_DIR=/app/logs
USER app
EXPOSE 8081
ENTRYPOINT ["/app/bin/Server"]
