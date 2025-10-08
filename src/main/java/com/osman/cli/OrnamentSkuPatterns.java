// OrnamentSkuPatterns.java
package com.osman.cli;

import java.util.regex.Pattern;

public final class OrnamentSkuPatterns {

    // Pattern to match SKU formats like "SKU-ABC123", "RN 45678", "PF: 7890A", etc.
    public static final Pattern ANY = Pattern.compile(
            "(?i)\\b(?:(?:SKU|SLU)\\s*[-:]?\\s*[A-Z]*\\d{2,}[A-Z0-9.-]*|(?:OR|RN|RM|PF|ORN)\\s*[-:]?\\s*\\d{2,}(?:\\s*[-/]?\\s*[A-Z0-9]+)*)\\b"
    );

    private OrnamentSkuPatterns() {}
}
