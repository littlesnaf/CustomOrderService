// OrnamentSkuPatterns.java
package com.osman.cli;

import java.util.regex.Pattern;

public final class OrnamentSkuPatterns {

    // Account1: UniqXmas (SKU, SLU, SKY)
    public static final Pattern ACCOUNT1 = Pattern.compile(
            "(?i)\\b(?:SKU|SLU|SKY)\\s*[-:]?\\s*[A-Z]*\\d{2,}[A-Z0-9.-]*\\b"
    );

    // Account2: OR/RM/PF/ORN
    public static final Pattern ACCOUNT2 = Pattern.compile(
            "(?i)\\b(?:OR|RM|PF|ORN)\\s*[-:]?\\s*\\d{2,}[A-Z0-9-]*\\b"
    );

    private OrnamentSkuPatterns() {}
}
