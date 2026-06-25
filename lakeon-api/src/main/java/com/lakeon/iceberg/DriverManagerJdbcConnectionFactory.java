package com.lakeon.iceberg;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

@Component
public class DriverManagerJdbcConnectionFactory implements JdbcConnectionFactory {
    @Override
    public Connection open(String jdbcUrl, String user, String password) throws SQLException {
        return DriverManager.getConnection(jdbcUrl, user, password);
    }
}
