package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.sql.Statement;

/** 保留既有订单；有效订单唯一键、版本号和事件记录支撑异步清运流程。 */
public class V2__TransportAndReliableOrders extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        String[] sql = {
            "ALTER TABLE orders ADD COLUMN version INT NOT NULL DEFAULT 0",
            "ALTER TABLE orders ADD COLUMN active_bin_id BIGINT NULL",
            "ALTER TABLE orders ADD COLUMN bin_cycle BIGINT NULL",
            "ALTER TABLE waste_bin ADD COLUMN fill_cycle BIGINT NOT NULL DEFAULT 0",
            "UPDATE orders SET active_bin_id = bin_id, bin_cycle = 0 WHERE status IN (1,2) AND id IN (SELECT active_order_id FROM waste_bin WHERE active_order_id IS NOT NULL)",
            "CREATE UNIQUE INDEX uq_order_active_bin ON orders(active_bin_id)",
            "CREATE UNIQUE INDEX uq_order_bin_cycle ON orders(bin_id, bin_cycle)",
            "CREATE TABLE catalog_revision (id INT PRIMARY KEY, revision BIGINT NOT NULL)",
            "INSERT INTO catalog_revision(id, revision) VALUES (1, 0)",
            "CREATE TABLE waste_order_event (id BIGINT PRIMARY KEY, bin_id BIGINT NOT NULL, bin_cycle BIGINT NOT NULL, source VARCHAR(16) NOT NULL, actor_id VARCHAR(64) NOT NULL, request_id VARCHAR(64) NOT NULL, reported_at TIMESTAMP NOT NULL, order_id BIGINT NULL, state VARCHAR(16) NOT NULL, publish_attempts INT NOT NULL DEFAULT 0, consume_attempts INT NOT NULL DEFAULT 0, next_attempt_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, last_error VARCHAR(255), CONSTRAINT uq_order_event_request UNIQUE(bin_id, source, actor_id, request_id))",
            "CREATE INDEX ix_order_event_dispatch ON waste_order_event(state, next_attempt_at)",
            "CREATE INDEX ix_order_event_bin ON waste_order_event(bin_id, bin_cycle, state)",
            "CREATE TABLE waste_order_route (order_id BIGINT PRIMARY KEY, origin VARCHAR(80) NOT NULL, destination VARCHAR(80) NOT NULL, distance_meters BIGINT NOT NULL, duration_seconds BIGINT NOT NULL, polyline MEDIUMTEXT NOT NULL, updated_at TIMESTAMP NOT NULL)",
            "CREATE TABLE driver_location (driver_id BIGINT PRIMARY KEY, order_id BIGINT NOT NULL, car_number VARCHAR(32), longitude DECIMAL(11,7) NOT NULL, latitude DECIMAL(10,7) NOT NULL, accuracy_meters DOUBLE, observed_at BIGINT NOT NULL, updated_at TIMESTAMP NOT NULL)"
        };
        try (Statement statement = context.getConnection().createStatement()) {
            for (String command : sql) statement.execute(command);
        }
    }
}
