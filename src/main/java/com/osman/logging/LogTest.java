package com.osman.logging;

import java.util.logging.Logger;

public class LogTest {
    public static void main(String[] args) {
        Logger logger = AppLogger.get();

        logger.info("✅ INFO: Logger test started");
        logger.warning("⚠️ WARNING: This is a test warning message");
        logger.severe("❌ ERROR: Simulated error message");

        System.out.println("Test log messages emitted.");

    }
}
