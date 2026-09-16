package org.example.fleet.delivery.routes;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;

public class EstadoPedidoRoute extends RouteBuilder {

    @Override
    public void configure() {

        configurarErrores();
        configurarEndpoint();
        configurarInicioPedido();
    }

    private void configurarEndpoint() {

        rest("/pedidos")
                .produces("application/json")

                .put("/{id}/iniciar")
                    .to("direct:iniciar-pedido");
    }

    private void configurarInicioPedido() {

        String consultarEstado = """
                SELECT
                    id,
                    estado
                FROM pedidos
                WHERE id = :?id
                """;

        String iniciarPedido = """
                WITH actualizado AS (
                    UPDATE pedidos
                    SET
                        estado = 'EN_CAMINO',
                        fecha_actualizacion = now()
                    WHERE id = :?id
                      AND estado = 'RECIBIDO'
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
                        'PEDIDO_EN_CAMINO',
                        '{"estado":"EN_CAMINO"}'::jsonb
                    FROM actualizado
                )
                SELECT
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
                FROM actualizado
                """;

        from("direct:iniciar-pedido")
                .routeId("procesar-iniciar-pedido")

                .setBody(constant(consultarEstado))

                .to(
                        "jdbc:dataSource"
                                + "?useHeadersAsParameters=true"
                )

                .process(exchange ->
                        validarPedidoParaInicio(exchange)
                )

                .setBody(constant(iniciarPedido))

                .to(
                        "jdbc:dataSource"
                                + "?useHeadersAsParameters=true"
                )

                .process(exchange -> {
                    List<Map<String, Object>> filas =
                            obtenerFilas(exchange);

                    if (filas.isEmpty()) {
                        throw new EstadoPedidoException(
                                "El pedido no pudo pasar a EN_CAMINO"
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
                        constant(200)
                )

                .setHeader(
                        Exchange.CONTENT_TYPE,
                        constant("application/json")
                );
    }

    private void validarPedidoParaInicio(
            Exchange exchange
    ) {
        List<Map<String, Object>> filas =
                obtenerFilas(exchange);

        if (filas.isEmpty()) {
            throw new PedidoNoEncontradoException(
                    "Pedido no encontrado"
            );
        }

        Object estadoActual = filas.get(0).get("estado");

        if (!"RECIBIDO".equals(String.valueOf(estadoActual))) {
            throw new EstadoPedidoException(
                    "El pedido debe estar en estado RECIBIDO "
                            + "para iniciar la entrega"
            );
        }
    }

    private void configurarErrores() {

        onException(PedidoNoEncontradoException.class)
                .handled(true)

                .setHeader(
                        Exchange.HTTP_RESPONSE_CODE,
                        constant(404)
                )

                .setHeader(
                        Exchange.CONTENT_TYPE,
                        constant("application/json")
                )

                .process(exchange ->
                        establecerError(
                                exchange,
                                "Pedido no encontrado"
                        )
                );

        onException(EstadoPedidoException.class)
                .handled(true)

                .setHeader(
                        Exchange.HTTP_RESPONSE_CODE,
                        constant(409)
                )

                .setHeader(
                        Exchange.CONTENT_TYPE,
                        constant("application/json")
                )

                .process(exchange -> {
                    Exception exception =
                            exchange.getProperty(
                                    Exchange.EXCEPTION_CAUGHT,
                                    Exception.class
                            );

                    establecerError(
                            exchange,
                            exception == null
                                    ? "Estado de pedido no válido"
                                    : exception.getMessage()
                    );
                });

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

                .process(exchange ->
                        establecerError(
                                exchange,
                                "No fue posible actualizar el pedido"
                        )
                );
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> obtenerFilas(
            Exchange exchange
    ) {
        Object body = exchange.getMessage().getBody();

        if (!(body instanceof List<?>)) {
            throw new EstadoPedidoException(
                    "La base de datos no devolvió "
                            + "un resultado válido"
            );
        }

        return (List<Map<String, Object>>) body;
    }

    private void establecerError(
            Exchange exchange,
            String mensaje
    ) {
        exchange.getMessage().setBody(
                "{\"error\":\""
                        + escaparJson(mensaje)
                        + "\"}"
        );
    }

    private String escaparJson(String texto) {
        if (texto == null) {
            return "Error inesperado";
        }

        return texto
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", " ")
                .replace("\n", " ");
    }

    private static class PedidoNoEncontradoException
            extends RuntimeException {

        PedidoNoEncontradoException(String mensaje) {
            super(mensaje);
        }
    }

    private static class EstadoPedidoException
            extends RuntimeException {

        EstadoPedidoException(String mensaje) {
            super(mensaje);
        }
    }
}