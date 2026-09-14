# syntax=docker/dockerfile:1

FROM gradle:9.6.1-jdk21-corretto AS builder

WORKDIR /build
COPY --chown=gradle:gradle . ./

RUN gradle :delivery-service:installDist -x test --no-daemon

FROM amazoncorretto:21-alpine

WORKDIR /app

COPY --from=builder \
    /build/delivery-service/build/install/delivery-service \
    /app

EXPOSE 8081

ENTRYPOINT ["/app/bin/delivery-service"]