FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/fast2sms-mock-gateway-0.0.1-SNAPSHOT.jar app.jar
ENV PORT=10000
EXPOSE 10000
USER 10001:10001
ENTRYPOINT ["java", "-jar", "app.jar"]
