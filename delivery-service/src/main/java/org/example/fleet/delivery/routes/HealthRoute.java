package org.example.fleet.delivery.routes;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;

public class HealthRoute extends RouteBuilder {

    @Override
    public void configure() {
        restConfiguration()
        .component("platform-http")
        .host("0.0.0.0")
        .port(8080);

        rest("/health")
                .get()
                .to("direct:health");

        from("direct:health")
                .routeId("delivery-health")
                .setBody(constant("SELECT 1 AS ok"))
                .to("jdbc:dataSource")
                .setHeader(
                        Exchange.HTTP_RESPONSE_CODE,
                        constant(200)
                )
                .setHeader(
                        Exchange.CONTENT_TYPE,
                        constant("application/json")
                )
                .setBody(constant(
                        "{\"status\":\"UP\",\"database\":\"UP\"}"
                ));
    }
}