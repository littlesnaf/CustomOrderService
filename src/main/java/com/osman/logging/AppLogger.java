package com.osman.logging;

import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.StreamHandler;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Provides a shared logger configuration for the application.
 */
public final class AppLogger {
    private static final Logger LOGGER = createLogger();

    private AppLogger() {
    }

    public static Logger get() {
        return LOGGER;
    }

    private static Logger createLogger() {
        Logger logger = Logger.getLogger("com.osman.CustomOrderService");
        logger.setUseParentHandlers(false);
        Formatter formatter = new Formatter() {
            @Override
            public String format(LogRecord record) {
                return "%s %s%n".formatted(record.getLevel().getName(), record.getMessage());
            }
        };

        var originalOut = System.out;
        StreamHandler consoleHandler = new StreamHandler(originalOut, formatter) {
            @Override
            public synchronized void publish(LogRecord record) {
                super.publish(record);
                flush();
            }
        };
        try {
            consoleHandler.setEncoding(UTF_8.name());
        } catch (Exception ignored) {
            // fall back to platform default when UTF-8 is unavailable
        }
        consoleHandler.setLevel(Level.ALL);
        logger.addHandler(consoleHandler);
        logger.setLevel(Level.INFO);

        try {
            DatabaseLogHandler dbHandler = new DatabaseLogHandler();
            dbHandler.setLevel(Level.INFO);
            logger.addHandler(dbHandler);
        } catch (IllegalStateException ex) {
            logger.info("Central logging disabled: " + ex.getMessage());
        } catch (Exception ex) {
            logger.warning("Failed to initialize central logging: " + ex.getMessage());
        }

        ConsoleOutputRedirector.redirect(logger);
        return logger;
    }
}
