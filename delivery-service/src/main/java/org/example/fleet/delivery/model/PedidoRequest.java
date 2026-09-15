package org.example.fleet.delivery.model;

public record PedidoRequest(
        String id,
        String clienteNombre,
        String clienteMsisdn,
        String clienteFcmId,
        String direccionTexto,
        Double latDestino,
        Double lonDestino,
        Integer radioLlegadaM,
        String repartidorDeviceId
) {
}