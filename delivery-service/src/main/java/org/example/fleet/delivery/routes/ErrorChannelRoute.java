package org.example.fleet.delivery.routes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;

public class ErrorChannelRoute extends RouteBuilder {

    @Override
    public void configure() {

        from("direct:delivery-error")
                .routeId("publicar-delivery-error")

                .process(exchange -> {
                    Map<String, Object> error =
                            new LinkedHashMap<>();

                    String motivo =
                            exchange.getMessage().getHeader(
                                    "errorReason",
                                    String.class
                            );

                    Exception exception =
                            exchange.getProperty(
                                    Exchange.EXCEPTION_CAUGHT,
                                    Exception.class
                            );

                    if ((motivo == null || motivo.isBlank())
                            && exception != null) {
                        motivo = exception.getMessage();
                    }

                    if (motivo == null || motivo.isBlank()) {
                        motivo = "ERROR_DESCONOCIDO";
                    }

                    Map<String, Object> payload =
                            new LinkedHashMap<>();

                    payload.put(
                            "pedidoId",
                            exchange.getMessage().getHeader(
                                    "pedidoId"
                            )
                    );

                    payload.put(
                            "deviceId",
                            exchange.getMessage().getHeader(
                                    "deviceId"
                            )
                    );

                    payload.put(
                            "latitude",
                            exchange.getMessage().getHeader(
                                    "latitude"
                            )
                    );

                    payload.put(
                            "longitude",
                            exchange.getMessage().getHeader(
                                    "longitude"
                            )
                    );

                    payload.put(
                            "positionTimestamp",
                            exchange.getMessage().getHeader(
                                    "positionTimestamp"
                            )
                    );

                    error.put(
                            "motivo",
                            motivo
                    );

                    error.put(
                            "payloadOriginal",
                            payload
                    );

                    error.put(
                            "timestamp",
                            Instant.now().toString()
                    );

                    exchange.getMessage().setBody(error);
                })

                .marshal()
                .json(JsonLibrary.Jackson)

                .to("amqp:queue:delivery.errors")

                .log(
                        "Mensaje enviado a delivery.errors: "
                                + "${body}"
                );

        from("amqp:queue:delivery.errors")
                .routeId("delivery-error-mock")

                .log(
                        "[DLQ MOCK] Mensaje no procesable: "
                                + "${body}"
                );
    }
}