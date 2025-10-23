package com.osman.logging;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Asynchronously writes JUL records to the central PostgreSQL log database.
 * Falls back to console-only logging when configuration is missing.
 */
public final class DatabaseLogHandler extends Handler {

    private static final String INSERT_SQL = """
        INSERT INTO app_logs (
            logged_at,
            level,
            logger,
            message,
            details,
            thread_name,
            host,
            thrown_type,
            thrown_msg
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private final BlockingQueue<LogRecord> queue = new LinkedBlockingQueue<>(1024);
    private final HikariDataSource dataSource;
    private final String hostName;
    private final Thread worker;

    private volatile boolean running = true;

    public DatabaseLogHandler() {
        DbConfig config = DbConfig.load();
        if (!config.enabled()) {
            throw new IllegalStateException("Central logging disabled: no JDBC configuration provided");
        }
        this.dataSource = createDataSource(config);
        this.hostName = resolveHostName();
        this.worker = new Thread(this::drainLoop, "db-log-writer");
        this.worker.setDaemon(true);
        this.worker.start();
        setLevel(Level.ALL);
    }

    private void drainLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                LogRecord record = queue.poll(1, TimeUnit.SECONDS);
                if (record != null) {
                    writeRecord(record);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                System.err.println("DatabaseLogHandler failure: " + ex.getMessage());
            }
        }

        // Flush remaining items on shutdown
        LogRecord record;
        while ((record = queue.poll()) != null) {
            try {
                writeRecord(record);
            } catch (Exception ex) {
                System.err.println("DatabaseLogHandler shutdown failure: " + ex.getMessage());
            }
        }
    }

    @Override
    public void publish(LogRecord record) {
        Objects.requireNonNull(record);
        if (!isLoggable(record) || !running) {
            return;
        }
        if (!queue.offer(record)) {
            queue.poll();
            queue.offer(record);
        }
    }

    @Override
    public void flush() {
        // no-op: records are persisted asynchronously
    }

    @Override
    public void close() throws SecurityException {
        running = false;
        worker.interrupt();
        try {
            worker.join(TimeUnit.SECONDS.toMillis(2));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        dataSource.close();
    }

    private void writeRecord(LogRecord record) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            statement.setTimestamp(1, Timestamp.from(Instant.ofEpochMilli(record.getMillis())));
            statement.setString(2, record.getLevel().getName());
            statement.setString(3, record.getLoggerName());
            statement.setString(4, renderMessage(record));
            statement.setString(5, formatParameters(record));
            statement.setString(6, threadName(record));
            statement.setString(7, hostName);
            if (record.getThrown() != null) {
                statement.setString(8, record.getThrown().getClass().getName());
                statement.setString(9, record.getThrown().getMessage());
            } else {
                statement.setString(8, null);
                statement.setString(9, null);
            }
            statement.executeUpdate();
        }
    }

    private static String formatParameters(LogRecord record) {
        Object[] params = record.getParameters();
        if (params == null || params.length == 0) {
            return null;
        }
        return Arrays.toString(params);
    }

    private static String renderMessage(LogRecord record) {
        String message = record.getMessage();
        Object[] params = record.getParameters();
        if (message == null) {
            return "";
        }
        if (params == null || params.length == 0) {
            return message;
        }
        try {
            return java.text.MessageFormat.format(message, params);
        } catch (IllegalArgumentException ex) {
            return message;
        }
    }

    private static String threadName(LogRecord record) {
        int threadId = record.getThreadID();
        return threadId == 0 ? null : "thread-" + threadId;
    }

    private static String resolveHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }

    private static HikariDataSource createDataSource(DbConfig config) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.url());
        hikariConfig.setUsername(config.username());
        hikariConfig.setPassword(config.password());
        hikariConfig.setMaximumPoolSize(config.poolSize());
        hikariConfig.setPoolName("CentralLoggingPool");
        hikariConfig.setAutoCommit(true);
        hikariConfig.setConnectionTestQuery("SELECT 1");
        hikariConfig.setInitializationFailTimeout(-1);
        return new HikariDataSource(hikariConfig);
    }

    private record DbConfig(String url, String username, String password, int poolSize, boolean enabled) {

        static DbConfig load() {
            Properties fileProps = loadFileProperties();
            String url = firstNonBlank(
                System.getProperty("logging.jdbc.url"),
                System.getenv("LOGGING_JDBC_URL"),
                fileProps.getProperty("jdbc.url")
            );
            String username = firstNonBlank(
                System.getProperty("logging.jdbc.user"),
                System.getenv("LOGGING_JDBC_USER"),
                fileProps.getProperty("jdbc.username")
            );
            String password = firstNonBlank(
                System.getProperty("logging.jdbc.pass"),
                System.getenv("LOGGING_JDBC_PASS"),
                fileProps.getProperty("jdbc.password")
            );
            int poolSize = parsePoolSize(
                firstNonBlank(
                    System.getProperty("logging.jdbc.poolSize"),
                    System.getenv("LOGGING_JDBC_POOL"),
                    fileProps.getProperty("jdbc.poolSize")
                )
            );
            boolean enabled = url != null && !url.isBlank();
            return new DbConfig(url, username, password, poolSize, enabled);
        }

        private static Properties loadFileProperties() {
            Properties props = new Properties();
            try (InputStream stream = DatabaseLogHandler.class
                .getClassLoader()
                .getResourceAsStream("logging-db.properties")) {
                if (stream != null) {
                    props.load(stream);
                }
            } catch (IOException ignored) {
                // ignore malformed property file and fall back to env/system props
            }
            return props;
        }

        private static String firstNonBlank(String... values) {
            if (values == null) {
                return null;
            }
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
            return null;
        }

        private static int parsePoolSize(String raw) {
            try {
                return raw == null ? 5 : Math.max(1, Integer.parseInt(raw));
            } catch (NumberFormatException ex) {
                return 5;
            }
        }
    }
}
