# syntax=docker/dockerfile:1
FROM scratch AS m2-cache
FROM gradle:9.6.1-jdk21-corretto AS builder
WORKDIR /build
RUN chown gradle:gradle /build

COPY --chown=gradle:gradle . ./

RUN --mount=type=bind,from=m2-cache,target=/home/gradle/.m2 \
    gradle :events-consumer:build -x test --no-daemon

FROM amazoncorretto:21-alpine
WORKDIR /app
COPY --from=builder /build/events-consumer/build/libs/events-consumer-1.0.0.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]


