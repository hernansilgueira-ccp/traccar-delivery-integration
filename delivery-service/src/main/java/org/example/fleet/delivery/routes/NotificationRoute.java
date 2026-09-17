package org.example.fleet.delivery.routes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;

public class NotificationRoute extends RouteBuilder {

    @Override
    public void configure() {

        onException(Exception.class)
                .handled(true)
                .log(
                        "Error en notificación PUSH: "
                                + "${exception.message}"
                );

        configurarPublicacion();
        configurarConsumidorMock();
    }

    private void configurarPublicacion() {

        String buscarNotificacionPendiente = """
        WITH reclamada AS (
            UPDATE pedido_eventos
            SET notificado = true
            WHERE pedido_id = :?pedidoId
              AND hito = :?hito
              AND notificado = false
            RETURNING
                pedido_id,
                hito
        )
        SELECT
            r.pedido_id AS "pedidoId",
            r.hito,
            p.cliente_nombre AS "clienteNombre",
            p.cliente_msisdn AS "clienteMsisdn",
            p.cliente_fcm_id AS "destinoFcmId"
        FROM reclamada r
        INNER JOIN pedidos p
            ON p.id = r.pedido_id
        """;

        from("direct:publicar-notificacion")
                .routeId("publicar-notificacion-push")

                .setBody(
                        constant(buscarNotificacionPendiente)
                )

                .to(
                        "jdbc:dataSource"
                                + "?useHeadersAsParameters=true"
                )

                .choice()

                    .when(simple("${body.size} == 0"))

                        .log(
                                "Notificación omitida por idempotencia: "
                                        + "pedido=${header.pedidoId}, "
                                        + "hito=${header.hito}"
                        )

                        .stop()

                    .otherwise()

                        .process(exchange ->
                                prepararNotificacion(exchange)
                        )

                        .marshal()
                        .json(JsonLibrary.Jackson)

                        .to(
                                "amqp:queue:delivery.notifications"
                        )

                        .log(
                                "Notificación enviada a Artemis: "
                                        + "pedido=${header.pedidoId}, "
                                        + "hito=${header.hito}"
                        )

                .end();
    }

    private void configurarConsumidorMock() {

    from("amqp:queue:delivery.notifications")
            .routeId("push-notification-mock")

            .unmarshal()
            .json(Map.class)

            .process(exchange -> {
                Map<?, ?> notificacion =
                        exchange.getMessage().getBody(
                                Map.class
                        );

                exchange.getMessage().setHeader(
                        "pedidoId",
                        notificacion.get("pedidoId")
                );

                exchange.getMessage().setHeader(
                        "hito",
                        notificacion.get("hito")
                );
            })

            .log(
                    "[PUSH MOCK] pedido=${header.pedidoId}, "
                            + "hito=${header.hito}, "
                            + "mensaje=${body}"
            )

            .log(
                    "[PUSH MOCK] Notificación procesada "
                            + "correctamente"
            );
}

    private void prepararNotificacion(
            Exchange exchange
    ) {
        List<Map<String, Object>> filas =
                obtenerFilas(exchange);

        Map<String, Object> fila = filas.get(0);

        String pedidoId =
                String.valueOf(fila.get("pedidoId"));

        String hito =
                String.valueOf(fila.get("hito"));

        String[] mensaje =
                obtenerMensaje(hito);

        Map<String, Object> notificacion =
                new LinkedHashMap<>();

        notificacion.put(
                "canal",
                "PUSH"
        );

        notificacion.put(
                "pedidoId",
                pedidoId
        );

        notificacion.put(
                "hito",
                hito
        );

        notificacion.put(
                "titulo",
                mensaje[0]
        );

        notificacion.put(
                "cuerpo",
                mensaje[1]
        );

        notificacion.put(
                "clienteNombre",
                fila.get("clienteNombre")
        );

        notificacion.put(
                "clienteMsisdn",
                fila.get("clienteMsisdn")
        );

        notificacion.put(
                "destinoFcmId",
                fila.get("destinoFcmId")
        );

        exchange.getMessage().setHeader(
                "pedidoId",
                pedidoId
        );

        exchange.getMessage().setHeader(
                "hito",
                hito
        );

        exchange.getMessage().setBody(
                notificacion
        );
    }

    private String[] obtenerMensaje(String hito) {

        return switch (hito) {
            case "PEDIDO_EN_CAMINO" -> new String[] {
                    "Tu pedido está en camino",
                    "El repartidor salió hacia tu dirección."
            };

            case "PEDIDO_CERCA" -> new String[] {
                    "Tu pedido está cerca",
                    "El repartidor está próximo al destino."
            };

            case "PEDIDO_ENTREGADO" -> new String[] {
                    "Pedido entregado",
                    "La entrega fue confirmada correctamente."
            };

            default -> new String[] {
                    "Actualización de pedido",
                    "El pedido tiene una nueva actualización."
            };
        };
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> obtenerFilas(
            Exchange exchange
    ) {
        Object body = exchange.getMessage().getBody();

        if (!(body instanceof List<?>)) {
            throw new IllegalStateException(
                    "La consulta no devolvió una lista"
            );
        }

        return (List<Map<String, Object>>) body;
    }
}