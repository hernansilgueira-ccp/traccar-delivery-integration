package org.example.fleet.delivery.routes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PositionTrackingRouteTest {

    @Test
    void debeRetornarCeroParaLaMismaPosicion() {
        double distancia =
                PositionTrackingRoute.calcularDistancia(
                        -25.2867,
                        -57.6470,
                        -25.2867,
                        -57.6470
                );

        assertEquals(0.0, distancia, 0.01);
    }

    @Test
    void debeCalcularLaDistanciaDeLaPruebaLejana() {
        double distancia =
                PositionTrackingRoute.calcularDistancia(
                        -25.3200,
                        -57.6200,
                        -25.2867,
                        -57.6470
                );

        assertEquals(4591.04, distancia, 1.0);
    }

    @Test
    void laDistanciaDebeSerSimetrica() {
        double ida =
                PositionTrackingRoute.calcularDistancia(
                        -25.3200,
                        -57.6200,
                        -25.2867,
                        -57.6470
                );

        double vuelta =
                PositionTrackingRoute.calcularDistancia(
                        -25.2867,
                        -57.6470,
                        -25.3200,
                        -57.6200
                );

        assertEquals(ida, vuelta, 0.01);
    }

    @Test
    void debeDetectarUnaPosicionDentroDe150Metros() {
        double distancia =
                PositionTrackingRoute.calcularDistancia(
                        -25.2862,
                        -57.6470,
                        -25.2867,
                        -57.6470
                );

        assertTrue(distancia > 0);
        assertTrue(distancia <= 150);
    }

    @Test
    void debeDetectarUnaPosicionFueraDe150Metros() {
        double distancia =
                PositionTrackingRoute.calcularDistancia(
                        -25.3200,
                        -57.6200,
                        -25.2867,
                        -57.6470
                );

        assertTrue(distancia > 150);
    }
}