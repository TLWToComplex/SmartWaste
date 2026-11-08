package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/** 保留已有表和数据，仅创建缺失的表、补齐清运业务字段。 */
public class V1__WasteWorkflowSchema extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        execute(c, "CREATE TABLE IF NOT EXISTS `user` (id BIGINT PRIMARY KEY, name VARCHAR(64), phone VARCHAR(32), "
                + "username VARCHAR(64) NOT NULL UNIQUE, password VARCHAR(255) NOT NULL, address VARCHAR(255), "
                + "avatar VARCHAR(255), status INT NOT NULL DEFAULT 1, sex VARCHAR(16), id_number VARCHAR(64), integral INT NOT NULL DEFAULT 0)");
        execute(c, "CREATE TABLE IF NOT EXISTS driver (id BIGINT PRIMARY KEY, name VARCHAR(64), phone VARCHAR(32), "
                + "car_number VARCHAR(32), address VARCHAR(255), username VARCHAR(64) NOT NULL UNIQUE, "
                + "password VARCHAR(255) NOT NULL, update_time VARCHAR(40), status INT NOT NULL DEFAULT 1)");
        execute(c, "CREATE TABLE IF NOT EXISTS employee (id BIGINT PRIMARY KEY, username VARCHAR(64) NOT NULL UNIQUE, "
                + "name VARCHAR(64), password VARCHAR(255) NOT NULL, phone VARCHAR(32), sex VARCHAR(16), id_number VARCHAR(64), "
                + "status INT NOT NULL DEFAULT 1, create_time TIMESTAMP NULL, update_time TIMESTAMP NULL, create_user BIGINT, update_user BIGINT)");
        execute(c, "CREATE TABLE IF NOT EXISTS orders (id BIGINT PRIMARY KEY, number VARCHAR(64), status INT NOT NULL, "
                + "user_id BIGINT, user_name VARCHAR(64), order_time VARCHAR(40), over_time VARCHAR(40), remark VARCHAR(500), "
                + "driver_phone VARCHAR(32), user_phone VARCHAR(32), start_address VARCHAR(255), destination VARCHAR(255), "
                + "longitude VARCHAR(32), latitude VARCHAR(32), longitude_end VARCHAR(32), latitude_end VARCHAR(32), "
                + "account VARCHAR(64), account1 VARCHAR(64))");
        addColumn(c, "orders", "bin_id", "BIGINT NULL");
        addColumn(c, "orders", "station_id", "BIGINT NULL");
        addColumn(c, "orders", "driver_id", "BIGINT NULL");
        addColumn(c, "orders", "report_source", "VARCHAR(16) NULL");
        addColumn(c, "orders", "car_number", "VARCHAR(32) NULL");
        execute(c, "CREATE TABLE IF NOT EXISTS waste_station (id BIGINT PRIMARY KEY, name VARCHAR(64) NOT NULL, "
                + "address VARCHAR(255) NOT NULL, longitude DECIMAL(11,7) NOT NULL, latitude DECIMAL(10,7) NOT NULL, "
                + "status INT NOT NULL DEFAULT 1)");
        execute(c, "CREATE TABLE IF NOT EXISTS waste_bin (id BIGINT PRIMARY KEY, code VARCHAR(64) NOT NULL UNIQUE, "
                + "name VARCHAR(64) NOT NULL, address VARCHAR(255) NOT NULL, waste_type VARCHAR(64) NOT NULL, "
                + "longitude DECIMAL(11,7) NOT NULL, latitude DECIMAL(10,7) NOT NULL, station_id BIGINT NOT NULL, "
                + "status INT NOT NULL DEFAULT 1, fill_status INT NOT NULL DEFAULT 0, active_order_id BIGINT NULL, "
                + "last_report_time TIMESTAMP NULL, last_emptied_at TIMESTAMP NULL, "
                + "device_id VARCHAR(64) NULL UNIQUE, device_token_hash VARCHAR(255) NULL)");
        execute(c, "CREATE TABLE IF NOT EXISTS full_bin_report (id BIGINT PRIMARY KEY, bin_id BIGINT NOT NULL, "
                + "order_id BIGINT NOT NULL, source VARCHAR(16) NOT NULL, actor_id VARCHAR(64) NOT NULL, "
                + "request_id VARCHAR(64) NOT NULL, reported_at TIMESTAMP NOT NULL, "
                + "CONSTRAINT uq_full_report_request UNIQUE (bin_id, source, actor_id, request_id))");
        // 既有明文密码字段可能较短，按需扩容，以便登录时无损升级为 BCrypt。
        widenPassword(c, "user");
        widenPassword(c, "driver");
        widenPassword(c, "employee");
    }

    private void addColumn(Connection c, String table, String column, String definition) throws Exception {
        if (!hasColumn(c, table, column)) execute(c, "ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    private boolean hasColumn(Connection c, String table, String column) throws Exception {
        try (ResultSet rs = c.getMetaData().getColumns(c.getCatalog(), null, null, null)) {
            while (rs.next()) {
                if (table.equalsIgnoreCase(rs.getString("TABLE_NAME")) && column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) return true;
            }
        }
        return false;
    }

    private void widenPassword(Connection c, String table) throws Exception {
        try (ResultSet rs = c.getMetaData().getColumns(c.getCatalog(), null, null, null)) {
            while (rs.next()) {
                if (table.equalsIgnoreCase(rs.getString("TABLE_NAME")) && "password".equalsIgnoreCase(rs.getString("COLUMN_NAME"))
                        && rs.getInt("COLUMN_SIZE") < 255) {
                    execute(c, "ALTER TABLE `" + table + "` MODIFY COLUMN password VARCHAR(255)");
                    return;
                }
            }
        }
    }

    private void execute(Connection c, String sql) throws Exception {
        try (Statement statement = c.createStatement()) { statement.execute(sql); }
    }
}
