package com.osman.logging;

import java.util.logging.Logger;

public class LogTest {
    public static void main(String[] args) {
        Logger logger = AppLogger.get();

        logger.info("✅ INFO: Database logging test started");
        logger.warning("⚠️ WARNING: This is a test warning message");
        logger.severe("❌ ERROR: Simulated error message");

        System.out.println("Test log messages sent to database.");

        // ---- BURAYI EKLEYİN ----
        // Arka plan loglamasının bitmesi için zaman tanıyın.
        try {
            System.out.println("Waiting for logs to be written...");
            Thread.sleep(3000); // 3 saniye bekle
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        System.out.println("Test finished.");
        // ---- BİTİŞ ----
    }
}