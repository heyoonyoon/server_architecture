FROM eclipse-temurin:21.0.12.1_1-jdk-noble AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY Server/build.gradle Server/
COPY Client/build.gradle Client/
COPY Proto Proto
COPY Common Common
COPY Data Data
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon :Data:installDist

FROM eclipse-temurin:21.0.12.1_1-jre-noble
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home app
WORKDIR /app
COPY --from=build /workspace/Data/build/install/Data /app
RUN mkdir /app/logs /app/data && chown app:app /app/logs /app/data
ENV LOG_DIR=/app/logs DATA_DIR=/app/data
USER app
VOLUME ["/app/data"]
EXPOSE 9200
ENTRYPOINT ["/app/bin/Data"]
