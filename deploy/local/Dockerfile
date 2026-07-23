FROM eclipse-temurin:17-jre-jammy

RUN useradd --system --uid 10001 --create-home openagent
WORKDIR /app

COPY bootstrap/target/bootstrap-0.1.0-SNAPSHOT-exec.jar /app/openagent.jar
COPY skills /app/skills

USER 10001
EXPOSE 18956
ENTRYPOINT ["java", "-jar", "/app/openagent.jar"]
