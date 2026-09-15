package org.example.fleet.delivery.routes;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.example.fleet.delivery.model.PedidoRequest;

public class PedidoRoute extends RouteBuilder {

    private static final String COLUMNAS_RESPUESTA = """
            id,
            cliente_nombre AS "clienteNombre",
            cliente_msisdn AS "clienteMsisdn",
            cliente_fcm_id AS "clienteFcmId",
            direccion_texto AS "direccionTexto",
            lat_destino AS "latDestino",
            lon_destino AS "lonDestino",
            radio_llegada_m AS "radioLlegadaM",
            repartidor_device_id AS "repartidorDeviceId",
            estado,
            fecha_creacion AS "fechaCreacion",
            fecha_actualizacion AS "fechaActualizacion"
            """;

    @Override
    public void configure() {

        configurarManejoErrores();
        configurarEndpointsRest();
        configurarCrearPedido();
        configurarListarPedidos();
        configurarObtenerPedido();
    }

    private void configurarEndpointsRest() {

        rest("/pedidos")
                .produces("application/json")

                .post()
                    .consumes("application/json")
                    .to("direct:crear-pedido")

                .get()
                    .to("direct:listar-pedidos")

                .get("/{id}")
                    .to("direct:obtener-pedido");
    }

    private void configurarManejoErrores() {

        onException(IllegalArgumentException.class)
                .handled(true)
                .setHeader(
                        Exchange.HTTP_RESPONSE_CODE,
                        constant(400)
                )
                .setHeader(
                        Exchange.CONTENT_TYPE,
                        constant("application/json")
                )
                .process(exchange -> {
                    Exception exception = exchange.getProperty(
                            Exchange.EXCEPTION_CAUGHT,
                            Exception.class
                    );

                    exchange.getMessage().setBody(
                            jsonError(exception.getMessage())
                    );
                });

        onException(SQLException.class)
                .handled(true)
                .setHeader(
                        Exchange.CONTENT_TYPE,
                        constant("application/json")
                )
                .process(exchange -> {
                    Exception exception = exchange.getProperty(
                            Exchange.EXCEPTION_CAUGHT,
                            Exception.class
                    );

                    SQLException sqlException =
                            encontrarSQLException(exception);

                    String sqlState = sqlException == null
                            ? null
                            : sqlException.getSQLState();

                    if ("23505".equals(sqlState)) {
                        exchange.getMessage().setHeader(
                                Exchange.HTTP_RESPONSE_CODE,
                                409
                        );

                        exchange.getMessage().setBody(jsonError(
                                "El identificador ya existe o el repartidor "
                                        + "ya tiene un pedido activo"
                        ));

                    } else if ("23503".equals(sqlState)) {
                        exchange.getMessage().setHeader(
                                Exchange.HTTP_RESPONSE_CODE,
                                400
                        );

                        exchange.getMessage().setBody(jsonError(
                                "El repartidor indicado no existe"
                        ));

                    } else {
                        exchange.getMessage().setHeader(
                                Exchange.HTTP_RESPONSE_CODE,
                                500
                        );

                        exchange.getMessage().setBody(jsonError(
                                "No fue posible procesar el pedido"
                        ));
                    }
                });

        onException(Exception.class)
                .handled(true)
                .setHeader(
                        Exchange.HTTP_RESPONSE_CODE,
                        constant(500)
                )
                .setHeader(
                        Exchange.CONTENT_TYPE,
                        constant("application/json")
                )
                .process(exchange -> {
                    Exception exception = exchange.getProperty(
                            Exchange.EXCEPTION_CAUGHT,
                            Exception.class
                    );

                    String mensaje = exception == null
                            ? "Error interno del servidor"
                            : exception.getMessage();

                    exchange.getMessage().setBody(
                            jsonError(mensaje)
                    );
                });
    }

    private void configurarCrearPedido() {

        String sql = """
                WITH nuevo AS (
                    INSERT INTO pedidos (
                        id,
                        cliente_nombre,
                        cliente_msisdn,
                        cliente_fcm_id,
                        direccion_texto,
                        lat_destino,
                        lon_destino,
                        radio_llegada_m,
                        repartidor_device_id,
                        estado
                    )
                    VALUES (
                        :?pedidoId,
                        :?clienteNombre,
                        :?clienteMsisdn,
                        NULLIF(:?clienteFcmId, ''),
                        NULLIF(:?direccionTexto, ''),
                        :?latDestino,
                        :?lonDestino,
                        :?radioLlegadaM,
                        :?repartidorDeviceId,
                        'RECIBIDO'
                    )
                    RETURNING *
                ),
                evento AS (
                    INSERT INTO pedido_eventos (
                        pedido_id,
                        hito,
                        detalle
                    )
                    SELECT
                        id,
                        'PEDIDO_RECIBIDO',
                        '{"estado":"RECIBIDO"}'::jsonb
                    FROM nuevo
                )
                SELECT
                """ + COLUMNAS_RESPUESTA + """
                FROM nuevo
                """;

        from("direct:crear-pedido")
                .routeId("procesar-crear-pedido")

                .unmarshal()
                .json(
                        JsonLibrary.Jackson,
                        PedidoRequest.class
                )

                .process(exchange -> {
                    PedidoRequest pedido =
                            exchange.getMessage().getBody(
                                    PedidoRequest.class
                            );

                    prepararPedido(pedido, exchange);
                })

                .setBody(constant(sql))

                .to(
                        "jdbc:dataSource"
                                + "?useHeadersAsParameters=true"
                )

                .process(exchange -> {
                    List<Map<String, Object>> filas =
                            obtenerFilas(exchange);

                    if (filas.isEmpty()) {
                        throw new IllegalStateException(
                                "No fue posible crear el pedido"
                        );
                    }

                    exchange.getMessage().setBody(
                            filas.get(0)
                    );
                })

                .marshal()
                .json(JsonLibrary.Jackson)

                .setHeader(
                        Exchange.HTTP_RESPONSE_CODE,
                        constant(201)
                )

                .setHeader(
                        Exchange.CONTENT_TYPE,
                        constant("application/json")
                );
    }

    private void configurarListarPedidos() {

        String sql = """
                SELECT
                """ + COLUMNAS_RESPUESTA + """
                FROM pedidos
                ORDER BY fecha_creacion DESC
                """;

        from("direct:listar-pedidos")
                .routeId("procesar-listar-pedidos")

                .setBody(constant(sql))

                .to("jdbc:dataSource")

                .marshal()
                .json(JsonLibrary.Jackson)

                .setHeader(
                        Exchange.HTTP_RESPONSE_CODE,
                        constant(200)
                )

                .setHeader(
                        Exchange.CONTENT_TYPE,
                        constant("application/json")
                );
    }

    private void configurarObtenerPedido() {

        String sql = """
                SELECT
                """ + COLUMNAS_RESPUESTA + """
                FROM pedidos
                WHERE id = :?id
                """;

        from("direct:obtener-pedido")
                .routeId("procesar-obtener-pedido")

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

                        .process(exchange -> {
                            List<Map<String, Object>> filas =
                                    obtenerFilas(exchange);

                            exchange.getMessage().setBody(
                                    filas.get(0)
                            );
                        })

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

    private void prepararPedido(
            PedidoRequest pedido,
            Exchange exchange
    ) {

        if (pedido == null) {
            throw new IllegalArgumentException(
                    "El cuerpo JSON es obligatorio"
            );
        }

        validarTexto(
                pedido.id(),
                "id"
        );

        validarTexto(
                pedido.clienteNombre(),
                "clienteNombre"
        );

        validarTexto(
                pedido.clienteMsisdn(),
                "clienteMsisdn"
        );

        validarTexto(
                pedido.repartidorDeviceId(),
                "repartidorDeviceId"
        );

        if (pedido.latDestino() == null
                || pedido.latDestino() < -90
                || pedido.latDestino() > 90) {

            throw new IllegalArgumentException(
                    "latDestino debe estar entre -90 y 90"
            );
        }

        if (pedido.lonDestino() == null
                || pedido.lonDestino() < -180
                || pedido.lonDestino() > 180) {

            throw new IllegalArgumentException(
                    "lonDestino debe estar entre -180 y 180"
            );
        }

        int radioLlegada = pedido.radioLlegadaM() == null
                ? 150
                : pedido.radioLlegadaM();

        if (radioLlegada <= 0) {
            throw new IllegalArgumentException(
                    "radioLlegadaM debe ser mayor que cero"
            );
        }

        exchange.getMessage().setHeader(
                "pedidoId",
                pedido.id().trim()
        );

        exchange.getMessage().setHeader(
                "clienteNombre",
                pedido.clienteNombre().trim()
        );

        exchange.getMessage().setHeader(
                "clienteMsisdn",
                pedido.clienteMsisdn().trim()
        );

        exchange.getMessage().setHeader(
                "clienteFcmId",
                textoOpcional(pedido.clienteFcmId())
        );

        exchange.getMessage().setHeader(
                "direccionTexto",
                textoOpcional(pedido.direccionTexto())
        );

        exchange.getMessage().setHeader(
                "latDestino",
                pedido.latDestino()
        );

        exchange.getMessage().setHeader(
                "lonDestino",
                pedido.lonDestino()
        );

        exchange.getMessage().setHeader(
                "radioLlegadaM",
                radioLlegada
        );

        exchange.getMessage().setHeader(
                "repartidorDeviceId",
                pedido.repartidorDeviceId().trim()
        );
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> obtenerFilas(
            Exchange exchange
    ) {
        Object body = exchange.getMessage().getBody();

        if (!(body instanceof List<?>)) {
            throw new IllegalStateException(
                    "La consulta no devolvió una lista de resultados"
            );
        }

        return (List<Map<String, Object>>) body;
    }

    private void validarTexto(
            String valor,
            String nombreCampo
    ) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException(
                    "El campo "
                            + nombreCampo
                            + " es obligatorio"
            );
        }
    }

    private String textoOpcional(String valor) {
        return valor == null
                ? ""
                : valor.trim();
    }

    private SQLException encontrarSQLException(
            Throwable error
    ) {
        Throwable actual = error;

        while (actual != null) {
            if (actual instanceof SQLException sqlException) {
                return sqlException;
            }

            actual = actual.getCause();
        }

        return null;
    }

    private String jsonError(String mensaje) {

        String mensajeSeguro = mensaje == null
                ? "Error inesperado"
                : mensaje
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\r", " ")
                    .replace("\n", " ");

        return "{\"error\":\""
                + mensajeSeguro
                + "\"}";
    }
}