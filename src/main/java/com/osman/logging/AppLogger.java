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
        Logger logger = Logger.getLogger("com.osman.mugeditor");
        logger.setUseParentHandlers(false);
        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.INFO);
        handler.setFormatter(new Formatter() {
            @Override
            public String format(LogRecord record) {
                return "%s %s%n".formatted(record.getLevel().getName(), record.getMessage());
            }
        });
        logger.addHandler(handler);
        logger.setLevel(Level.INFO);
        return logger;
    }
}
