FROM eclipse-temurin:21.0.12.1_1-jdk-noble AS descriptor
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY Server/build.gradle Server/
COPY Proto Proto
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon :Proto:generateProto

FROM envoyproxy/envoy:v1.40.0
COPY --from=descriptor /workspace/Proto/build/generated/source/proto/main/descriptor_set.desc /etc/envoy/server_client.pb
