package com.osman.integration.amazon;

import java.text.Normalizer;

/**
 * Utility to normalize user-facing strings (e.g. buyer names) into filesystem safe folder names.
 */
final class NameSanitizer {

    private NameSanitizer() {
    }

    static String sanitize(String input) {
        if (input == null || input.isBlank()) {
            return "UNKNOWN";
        }

        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
            .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");

        String cleaned = normalized.replaceAll("[^A-Za-z0-9 _.-]", "");
        cleaned = cleaned.trim();
        if (cleaned.isBlank()) {
            return "UNKNOWN";
        }

        // Collapse sequences of spaces into single space for readability.
        cleaned = cleaned.replaceAll("\\s{2,}", " ");
        return cleaned;
    }

    static String sanitizeForFolder(String input) {
        String sanitized = sanitize(input);
        sanitized = sanitized.replace('/', '-')
            .replace('\\', '-')
            .replace(':', '-')
            .replace('*', '-')
            .replace('?', '-')
            .replace('"', '\'')
            .replace('<', '(')
            .replace('>', ')')
            .replace('|', '-');
        sanitized = sanitized.replaceAll("\\s{2,}", " ");
        sanitized = sanitized.trim();
        sanitized = sanitized.replace(' ', '_');
        if (sanitized.length() > 80) {
            sanitized = sanitized.substring(0, 80);
        }
        return sanitized;
    }
}
