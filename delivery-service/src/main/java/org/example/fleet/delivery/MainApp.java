package org.example.fleet.delivery;

import org.apache.camel.main.Main;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MainApp {

    private static final Logger log =
            LoggerFactory.getLogger(MainApp.class);

    public static void main(String[] args) throws Exception {
        log.info("Starting Delivery Service");

        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(env(
                "DB_URL",
                "jdbc:postgresql://postgres:5432/delivery"
        ));
        dataSource.setUser(env("DB_USER", "delivery"));
        dataSource.setPassword(env("DB_PASSWORD", "delivery123"));

        Main main = new Main();
        main.bind("dataSource", dataSource);

        main.configure()
                .withBasePackageScan(
                        "org.example.fleet.delivery.routes"
                );

        main.run(args);
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank()
                ? defaultValue
                : value;
    }
}