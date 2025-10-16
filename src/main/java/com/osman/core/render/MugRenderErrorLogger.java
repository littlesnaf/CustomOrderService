package com.osman.core.render;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Persists mug rendering errors to a CSV inside the build output directory so
 * production issues can be reviewed later without digging through UI logs.
 */
public final class MugRenderErrorLogger {

    private static final Path LOG_FILE = Paths.get("target", "mug-render-errors.csv");
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private MugRenderErrorLogger() {
    }

    public static void logFailure(String stage,
                                  Path orderContext,
                                  Path leafFolder,
                                  Path readyFolder,
                                  String customerName,
                                  Exception exception) {
        String timestamp = TIMESTAMP_FORMAT.format(Instant.now());
        String message = (exception == null) ? "" : exception.getMessage();
        String exceptionType = (exception == null) ? "" : exception.getClass().getName();

        String[] columns = new String[] {
            timestamp,
            stage,
            pathToString(orderContext),
            pathToString(leafFolder),
            pathToString(readyFolder),
            customerName != null ? customerName : "",
            exceptionType,
            message != null ? message : ""
        };

        writeRow(columns);
    }

    private static String pathToString(Path path) {
        return path == null ? "" : path.toString();
    }

    private static void writeRow(String[] columns) {
        synchronized (MugRenderErrorLogger.class) {
            try {
                if (LOG_FILE.getParent() != null) {
                    Files.createDirectories(LOG_FILE.getParent());
                }
                boolean fileExists = Files.exists(LOG_FILE);
                try (BufferedWriter writer = Files.newBufferedWriter(
                    LOG_FILE,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
                )) {
                    if (!fileExists) {
                        writer.write("timestamp,stage,order_context,leaf_folder,ready_folder,customer,exception_type,message");
                        writer.newLine();
                    }
                    writer.write(toCsv(columns));
                    writer.newLine();
                }
            } catch (IOException ioEx) {
                System.err.println("Failed to write mug render error log: " + ioEx.getMessage());
            }
        }
    }

    private static String toCsv(String[] columns) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(columns[i]));
        }
        return sb.toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuotes = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
        String escaped = value.replace("\"", "\"\"");
        if (needsQuotes) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }
}
