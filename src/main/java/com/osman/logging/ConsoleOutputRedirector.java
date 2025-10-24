package com.osman.logging;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Redirects {@link System#out} and {@link System#err} to the shared JUL logger so
 * legacy console prints are persisted by {@link DatabaseLogHandler}.
 */
final class ConsoleOutputRedirector {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private ConsoleOutputRedirector() {
    }

    static void redirect(Logger logger) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }

        System.setOut(createPrintStream(logger, Level.INFO));
        System.setErr(createPrintStream(logger, Level.SEVERE));
    }

    private static PrintStream createPrintStream(Logger logger, Level level) {
        OutputStream bridge = new LoggerOutputStream(logger, level);
        return new PrintStream(bridge, true, StandardCharsets.UTF_8);
    }

    private static final class LoggerOutputStream extends OutputStream {
        private final Logger logger;
        private final Level level;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);

        LoggerOutputStream(Logger logger, Level level) {
            this.logger = logger;
            this.level = level;
        }

        @Override
        public synchronized void write(int b) {
            if (b == '\r') {
                return;
            }
            if (b == '\n') {
                flushBuffer();
                return;
            }
            buffer.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            for (int i = 0; i < len; i++) {
                write(b[off + i]);
            }
        }

        @Override
        public synchronized void flush() {
            flushBuffer();
        }

        private void flushBuffer() {
            if (buffer.size() == 0) {
                return;
            }
            String message = buffer.toString(StandardCharsets.UTF_8);
            buffer.reset();
            if (!message.isEmpty()) {
                logger.log(level, message);
            }
        }
    }
}
