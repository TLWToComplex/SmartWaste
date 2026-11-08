package com.example.shizhan.rubbish;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import static org.junit.jupiter.api.Assertions.*;

class WasteSchemaMigrationTest {
    @Test
    void upgradesLegacyTablesWithoutReplacingDataAndCanRestart() throws Exception {
        String url = "jdbc:h2:mem:legacy_waste_schema;MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", ""); Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE orders(id BIGINT PRIMARY KEY, status INT, order_time VARCHAR(40))");
            sql.execute("INSERT INTO orders VALUES (42, 3, '2026-01-01 12:00:00')");
            sql.execute("CREATE TABLE `user`(id BIGINT PRIMARY KEY, username VARCHAR(64), password VARCHAR(32))");
            sql.execute("INSERT INTO `user` VALUES (12, 'legacy-account', 'legacy-password')");
        }
        Flyway flyway = Flyway.configure().dataSource(url, "sa", "").baselineOnMigrate(true).baselineVersion("0").load();
        flyway.migrate();
        flyway.migrate();
        try (Connection connection = DriverManager.getConnection(url, "sa", ""); Statement sql = connection.createStatement()) {
            try (ResultSet rows = sql.executeQuery("SELECT status, order_time, bin_id, driver_id FROM orders WHERE id = 42")) {
                assertTrue(rows.next()); assertEquals(3, rows.getInt(1)); assertEquals("2026-01-01 12:00:00", rows.getString(2));
                assertNull(rows.getObject(3)); assertNull(rows.getObject(4));
            }
            try (ResultSet rows = sql.executeQuery("SELECT password FROM `user` WHERE id = 12")) {
                assertTrue(rows.next()); assertEquals("legacy-password", rows.getString(1));
            }
            sql.execute("UPDATE `user` SET password = '" + new String(new char[60]).replace('\0', 'a') + "' WHERE id = 12");
            try (ResultSet rows = sql.executeQuery("SELECT COUNT(*) FROM waste_bin")) { rows.next(); assertEquals(0, rows.getInt(1)); }
        }
    }
}
