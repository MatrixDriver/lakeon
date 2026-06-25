package com.lakeon.iceberg;

import java.sql.Connection;
import java.sql.SQLException;

public interface JdbcConnectionFactory {
    Connection open(String jdbcUrl, String user, String password) throws SQLException;
}
