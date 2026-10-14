FROM eclipse-temurin:21.0.12.1_1-jdk-noble AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY Proto Proto
COPY Server Server
COPY Client/build.gradle Client/build.gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon :Server:installDist

FROM eclipse-temurin:21.0.12.1_1-jre-noble
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /workspace/Server/build/install/Server /app
USER app
EXPOSE 8081
ENTRYPOINT ["/app/bin/Server"]
