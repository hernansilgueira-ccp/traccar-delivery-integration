# syntax=docker/dockerfile:1
FROM scratch AS m2-cache
FROM gradle:9.6.1-jdk21-corretto AS builder
WORKDIR /build
RUN chown gradle:gradle /build

COPY --chown=gradle:gradle . ./

RUN --mount=type=bind,from=m2-cache,target=/home/gradle/.m2 \
    gradle :broker:installDist -x test --no-daemon

FROM amazoncorretto:21-alpine
WORKDIR /app
COPY --from=builder /build/broker/build/install/broker /app
EXPOSE 8080
ENTRYPOINT ["/app/bin/broker"]



