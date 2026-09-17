package org.example.fleet.delivery.routes;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;

public class TrackingRoute extends RouteBuilder {

    @Override
    public void configure() {

        configurarErrores();
        configurarEndpoint();
        configurarConsulta();
    }

    private void configurarEndpoint() {

        rest("/pedidos")
                .produces("application/json")

                .get("/{id}/tracking")
                    .to("direct:consultar-tracking");
    }

    private void configurarConsulta() {

        String sql = """
                SELECT
                    p.id AS "pedidoId",
                    p.estado,
                    upp.lat,
                    upp.lon,
                    upp.velocidad_kmh AS "velocidadKmh",
                    upp.distancia_destino_m
                        AS "distanciaDestinoM",
                    upp.timestamp AS "positionTimestamp"
                FROM pedidos p
                LEFT JOIN pedido_ultima_posicion upp
                    ON upp.pedido_id = p.id
                WHERE p.id = :?id
                """;

        from("direct:consultar-tracking")
                .routeId("consultar-tracking-pedido")

                .setBody(constant(sql))

                .to(
                        "jdbc:dataSource"
                                + "?useHeadersAsParameters=true"
                )

                .choice()

                    .when(simple("${body.size} == 0"))

                        .setHeader(
                                Exchange.HTTP_RESPONSE_CODE,
                                constant(404)
                        )

                        .setHeader(
                                Exchange.CONTENT_TYPE,
                                constant("application/json")
                        )

                        .setBody(constant(
                                "{\"error\":\"Pedido no encontrado\"}"
                        ))

                    .otherwise()

                        .process(exchange ->
                                construirRespuesta(exchange)
                        )

                        .marshal()
                        .json(JsonLibrary.Jackson)

                        .setHeader(
                                Exchange.HTTP_RESPONSE_CODE,
                                constant(200)
                        )

                        .setHeader(
                                Exchange.CONTENT_TYPE,
                                constant("application/json")
                        )

                .end();
    }

    private void configurarErrores() {

        onException(SQLException.class)
                .handled(true)

                .setHeader(
                        Exchange.HTTP_RESPONSE_CODE,
                        constant(500)
                )

                .setHeader(
                        Exchange.CONTENT_TYPE,
                        constant("application/json")
                )

                .setBody(constant(
                        "{\"error\":\"No fue posible "
                                + "consultar el tracking\"}"
                ));
    }

    private void construirRespuesta(
            Exchange exchange
    ) {
        List<Map<String, Object>> filas =
                obtenerFilas(exchange);

        Map<String, Object> fila = filas.get(0);

        Map<String, Object> respuesta =
                new LinkedHashMap<>();

        respuesta.put(
                "pedidoId",
                fila.get("pedidoId")
        );

        respuesta.put(
                "estado",
                fila.get("estado")
        );

        if (fila.get("lat") == null) {
            respuesta.put("posicion", null);
        } else {
            Map<String, Object> posicion =
                    new LinkedHashMap<>();

            posicion.put(
                    "lat",
                    fila.get("lat")
            );

            posicion.put(
                    "lon",
                    fila.get("lon")
            );

            posicion.put(
                    "velocidadKmh",
                    fila.get("velocidadKmh")
            );

            posicion.put(
                    "distanciaDestinoM",
                    fila.get("distanciaDestinoM")
            );

            posicion.put(
                    "timestamp",
                    fila.get("positionTimestamp")
            );

            respuesta.put(
                    "posicion",
                    posicion
            );
        }

        exchange.getMessage().setBody(respuesta);
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