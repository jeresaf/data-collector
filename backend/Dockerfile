FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests -B

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# XLSForm conversion is intentionally delegated to the official pyxform CLI.
# Keep it in an isolated virtualenv and expose the executable path to Spring.
RUN apk add --no-cache python3 py3-pip py3-virtualenv \
    && python3 -m virtualenv /opt/pyxform \
    && /opt/pyxform/bin/pip install --no-cache-dir "pyxform==4.5.0"

ENV PYXFORM_COMMAND=/opt/pyxform/bin/xls2xform

COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
