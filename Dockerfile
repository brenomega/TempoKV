FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew
RUN ./gradlew --no-daemon --version

COPY src src
RUN ./gradlew --no-daemon clean build

FROM eclipse-temurin:25-jre

RUN groupadd --system tempokv \
    && useradd --system --gid tempokv --home-dir /opt/tempokv --create-home tempokv \
    && mkdir /data \
    && chown tempokv:tempokv /data

WORKDIR /opt/tempokv
COPY --from=build --chown=tempokv:tempokv /workspace/build/libs/tempokv-0.1.0.jar app.jar

USER tempokv
ENV TEMPOKV_DATA_DIR=/data
VOLUME ["/data"]

ENTRYPOINT ["java", "-jar", "/opt/tempokv/app.jar"]
