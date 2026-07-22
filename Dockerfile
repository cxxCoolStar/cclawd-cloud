FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /workspace
COPY . .
RUN chmod +x mvnw && ./mvnw -DskipTests package

FROM eclipse-temurin:17-jre-jammy

RUN useradd --system --uid 10001 --create-home openagent
WORKDIR /app
COPY --from=build /workspace/bootstrap/target/bootstrap-0.1.0-SNAPSHOT.jar /app/openagent.jar
COPY --from=build /workspace/skills /app/skills

USER 10001
EXPOSE 18953
ENTRYPOINT ["java", "-jar", "/app/openagent.jar"]
