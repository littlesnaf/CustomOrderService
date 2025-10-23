package com.osman.logging;

import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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
        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.INFO);
        handler.setLevel(Level.ALL);
        handler.setFormatter(new Formatter() {
            @Override
            public String format(LogRecord record) {
                return "%s %s%n".formatted(record.getLevel().getName(), record.getMessage());
            }
        });
        logger.addHandler(handler);
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
        return logger;
    }
}
