package com.osman.logging;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseLogHandlerTest {

    private static final String JDBC_URL = "jdbc:h2:mem:test-logs;MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
    private static final String JDBC_USER = "sa";
    private static final String JDBC_PASS = "";

    @BeforeAll
    static void setUpDatabase() throws SQLException {
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("H2 driver not found on classpath", e);
        }
        System.setProperty("logging.jdbc.url", JDBC_URL);
        System.setProperty("logging.jdbc.user", JDBC_USER);
        System.setProperty("logging.jdbc.pass", JDBC_PASS);
        System.setProperty("logging.jdbc.poolSize", "2");

        try (Connection connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASS);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS app_logs");
            statement.execute("""
                CREATE TABLE app_logs (
                    logged_at   TIMESTAMP NOT NULL,
                    level       VARCHAR(16) NOT NULL,
                    logger      VARCHAR(128),
                    message     TEXT,
                    details     TEXT,
                    thread_name VARCHAR(64),
                    host        VARCHAR(128),
                    thrown_type VARCHAR(256),
                    thrown_msg  TEXT
                )
                """);
        }
    }

    @AfterEach
    void clearTable() throws SQLException {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASS);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM app_logs");
        }
    }

    @AfterAll
    static void tearDown() {
        System.clearProperty("logging.jdbc.url");
        System.clearProperty("logging.jdbc.user");
        System.clearProperty("logging.jdbc.pass");
        System.clearProperty("logging.jdbc.poolSize");
    }

    @Test
    void publishPersistsLogRecord() throws Exception {
        DatabaseLogHandler handler = new DatabaseLogHandler();
        boolean closed = false;
        try {
            LogRecord record = new LogRecord(Level.INFO, "Test message");
            record.setLoggerName("test.logger");
            record.setParameters(new Object[]{"alpha", 42});

            handler.publish(record);

            handler.close();
            closed = true;

            try (Connection connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASS);
                 PreparedStatement statement = connection.prepareStatement(
                     "SELECT level, logger, message, details FROM app_logs")) {
                ResultSet resultSet = statement.executeQuery();
                assertTrue(resultSet.next(), "No log record persisted");
                assertEquals("INFO", resultSet.getString("level"));
                assertEquals("test.logger", resultSet.getString("logger"));
                assertEquals("Test message", resultSet.getString("message"));
                assertEquals("[alpha, 42]", resultSet.getString("details"));
            }
        } finally {
            if (!closed) {
                handler.close();
            }
        }
    }

}
