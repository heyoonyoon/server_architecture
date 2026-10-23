FROM eclipse-temurin:21.0.12.1_1-jdk-noble AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY Server/build.gradle Server/
COPY Data/build.gradle Data/
COPY Common/build.gradle Common/
COPY Proto Proto
COPY Client Client
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon :Client:installDist

FROM eclipse-temurin:21.0.12.1_1-jre-noble
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home app
WORKDIR /app
COPY --from=build /workspace/Client/build/install/Client /app
ENV SERVER_HOST=envoy SERVER_PORT=8080
USER app
ENTRYPOINT ["/app/bin/Client"]
