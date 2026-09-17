package org.example.fleet.delivery.routes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.example.fleet.model.VehiclePosition;

public class PositionTrackingRoute extends RouteBuilder {

    private static final double RADIO_TIERRA_M = 6_371_000.0;

    @Override
    public void configure() {

        onException(Exception.class)
                .handled(true)
                .log(
                        "Error procesando posición GPS: "
                                + "${exception.message}"
                );

        String buscarPedidoActivo = """
                SELECT
                    id AS "pedidoId",
                    lat_destino AS "latDestino",
                    lon_destino AS "lonDestino",
                    radio_llegada_m AS "radioLlegadaM",
                    estado
                FROM pedidos
                WHERE repartidor_device_id = :?deviceId
                  AND estado IN ('EN_CAMINO', 'CERCA')
                ORDER BY fecha_creacion DESC
                LIMIT 1
                """;

        String guardarUltimaPosicion = """
                INSERT INTO pedido_ultima_posicion (
                    pedido_id,
                    device_id,
                    lat,
                    lon,
                    velocidad_kmh,
                    distancia_destino_m,
                    timestamp
                )
                VALUES (
                    :?pedidoId,
                    :?deviceId,
                    :?latitude,
                    :?longitude,
                    :?speedKmh,
                    :?distanciaDestinoM,
                    CAST(:?positionTimestamp AS timestamptz)
                )
                ON CONFLICT (pedido_id)
                DO UPDATE SET
                    device_id = EXCLUDED.device_id,
                    lat = EXCLUDED.lat,
                    lon = EXCLUDED.lon,
                    velocidad_kmh = EXCLUDED.velocidad_kmh,
                    distancia_destino_m =
                            EXCLUDED.distancia_destino_m,
                    timestamp = EXCLUDED.timestamp
                """;

        String marcarComoCerca = """
        WITH actualizado AS (
            UPDATE pedidos
            SET
                estado = 'CERCA',
                fecha_actualizacion = now()
            WHERE id = :?pedidoId
              AND estado = 'EN_CAMINO'
              AND CAST(
                    :?distanciaDestinoM
                    AS double precision
                  ) <= CAST(
                    :?radioLlegadaM
                    AS integer
                  )
            RETURNING id
        )
        INSERT INTO pedido_eventos (
            pedido_id,
            hito,
            detalle
        )
        SELECT
            id,
            'PEDIDO_CERCA',
            jsonb_build_object(
                'estado',
                'CERCA',
                'distanciaDestinoM',
                CAST(
                    :?distanciaDestinoM
                    AS double precision
                )
            )
        FROM actualizado
        ON CONFLICT (pedido_id, hito)
        DO NOTHING
        """;

        from(
                "amqp:topic:vehicle.positions"
                        + "?subscriptionDurable=true"
                        + "&durableSubscriptionName=delivery-tracking"
                        + "&clientId=delivery-tracking"
        )
                .routeId("delivery-position-tracking")

                .unmarshal()
                .json(VehiclePosition.class)

                .filter(simple("${body.valid} == true"))

                .process(exchange -> {
                    VehiclePosition posicion =
                            exchange.getMessage().getBody(
                                    VehiclePosition.class
                            );

                    validarPosicion(posicion);

                    exchange.getMessage().setHeader(
                            "deviceId",
                            posicion.deviceId()
                    );

                    exchange.getMessage().setHeader(
                            "latitude",
                            posicion.latitude()
                    );

                    exchange.getMessage().setHeader(
                            "longitude",
                            posicion.longitude()
                    );

                    exchange.getMessage().setHeader(
                            "speedKmh",
                            posicion.speedKmh()
                    );

                    exchange.getMessage().setHeader(
                            "positionTimestamp",
                            timestampValido(posicion.timestamp())
                    );
                })

                .setBody(constant(buscarPedidoActivo))

                .to(
                        "jdbc:dataSource"
                                + "?useHeadersAsParameters=true"
                )

                .choice()

                    .when(simple("${body.size} == 0"))

                        .log(
                                "No existe pedido activo para "
                                        + "el dispositivo ${header.deviceId}"
                        )

                        .stop()

                    .otherwise()

                        .process(exchange ->
                                prepararSeguimiento(exchange)
                        )

                        .setBody(
                                constant(guardarUltimaPosicion)
                        )

                        .to(
                                "jdbc:dataSource"
                                        + "?useHeadersAsParameters=true"
                        )

                        .setBody(
                                constant(marcarComoCerca)
                        )

                        .to(
                                "jdbc:dataSource"
                                        + "?useHeadersAsParameters=true"
                        )

                        .log(
                                "Posición guardada: "
                                        + "pedido=${header.pedidoId}, "
                                        + "device=${header.deviceId}, "
                                        + "distancia="
                                        + "${header.distanciaDestinoM} m"
                        )

                .end();
    }

    private void prepararSeguimiento(
            Exchange exchange
    ) {
        List<Map<String, Object>> pedidos =
                obtenerFilas(exchange);

        Map<String, Object> pedido = pedidos.get(0);

        String pedidoId =
                String.valueOf(pedido.get("pedidoId"));

        double latDestino =
                numero(pedido.get("latDestino"));

        double lonDestino =
                numero(pedido.get("lonDestino"));

        int radioLlegada =
                ((Number) pedido.get("radioLlegadaM"))
                        .intValue();

        double latitude =
                numero(
                        exchange.getMessage().getHeader(
                                "latitude"
                        )
                );

        double longitude =
                numero(
                        exchange.getMessage().getHeader(
                                "longitude"
                        )
                );

        double distancia = calcularDistancia(
                latitude,
                longitude,
                latDestino,
                lonDestino
        );

        exchange.getMessage().setHeader(
                "pedidoId",
                pedidoId
        );

        exchange.getMessage().setHeader(
                "radioLlegadaM",
                radioLlegada
        );

        exchange.getMessage().setHeader(
                "distanciaDestinoM",
                distancia
        );
    }

    private void validarPosicion(
            VehiclePosition posicion
    ) {
        if (posicion == null) {
            throw new IllegalArgumentException(
                    "El mensaje de posición está vacío"
            );
        }

        if (posicion.deviceId() == null
                || posicion.deviceId().isBlank()) {
            throw new IllegalArgumentException(
                    "La posición no contiene deviceId"
            );
        }

        if (posicion.latitude() < -90
                || posicion.latitude() > 90) {
            throw new IllegalArgumentException(
                    "Latitud fuera del rango permitido"
            );
        }

        if (posicion.longitude() < -180
                || posicion.longitude() > 180) {
            throw new IllegalArgumentException(
                    "Longitud fuera del rango permitido"
            );
        }
    }

    private String timestampValido(
            String timestamp
    ) {
        if (timestamp == null || timestamp.isBlank()) {
            return Instant.now().toString();
        }

        try {
            return Instant.parse(timestamp).toString();
        } catch (Exception exception) {
            return Instant.now().toString();
        }
    }

    static double calcularDistancia(
            double latitudOrigen,
            double longitudOrigen,
            double latitudDestino,
            double longitudDestino
    ) {
        double lat1 = Math.toRadians(latitudOrigen);
        double lat2 = Math.toRadians(latitudDestino);

        double diferenciaLatitud =
                Math.toRadians(
                        latitudDestino - latitudOrigen
                );

        double diferenciaLongitud =
                Math.toRadians(
                        longitudDestino - longitudOrigen
                );

        double a =
                Math.sin(diferenciaLatitud / 2)
                        * Math.sin(diferenciaLatitud / 2)
                        + Math.cos(lat1)
                        * Math.cos(lat2)
                        * Math.sin(diferenciaLongitud / 2)
                        * Math.sin(diferenciaLongitud / 2);

        double c = 2 * Math.atan2(
                Math.sqrt(a),
                Math.sqrt(1 - a)
        );

        return Math.round(
                RADIO_TIERRA_M * c * 100.0
        ) / 100.0;
    }

    private double numero(Object valor) {
        if (!(valor instanceof Number numero)) {
            throw new IllegalArgumentException(
                    "Valor numérico inválido: " + valor
            );
        }

        return numero.doubleValue();
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