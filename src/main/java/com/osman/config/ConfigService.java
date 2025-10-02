package com.osman.config;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Central entry point for resolving configuration values with overrides and persisted preferences.
 */
public final class ConfigService {
    private static final String FONT_DIR_PROPERTY = "fontDir";
    private static final String PREF_KEY_FONT_DIR = "font.dir";

    private static final ConfigService INSTANCE = new ConfigService(PreferencesStore.global());

    private final PreferencesStore preferences;
    private final Path defaultFontDirectory;

    private ConfigService(PreferencesStore preferences) {
        this.preferences = preferences;
        this.defaultFontDirectory = resolveDefaultFontDirectory();
    }

    public static ConfigService getInstance() {
        return INSTANCE;
    }

    public Path getFontDirectory() {
        Optional<Path> persisted = preferences.getPath(PREF_KEY_FONT_DIR);
        return persisted.orElse(defaultFontDirectory);
    }

    public void setFontDirectory(Path fontDirectory) {
        if (fontDirectory == null) return;
        preferences.putPath(PREF_KEY_FONT_DIR, fontDirectory);
    }

    private Path resolveDefaultFontDirectory() {
        String override = System.getProperty(FONT_DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        String home = System.getProperty("user.home");
        Path desktopFonts = Paths.get(home, "Desktop", "Fonts");
        if (new File(desktopFonts.toString()).isDirectory()) {
            return desktopFonts;
        }
        return Paths.get(home);
    }
}
