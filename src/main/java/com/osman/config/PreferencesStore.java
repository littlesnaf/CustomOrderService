package com.osman.config;

import java.nio.file.Path;
import java.util.Optional;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Lightweight wrapper around {@link Preferences} so UI modules can persist window and directory state.
 */
public final class PreferencesStore {
    private static final String ROOT_NODE = "com/osman/mugeditor";

    private final Preferences delegate;

    private PreferencesStore(Preferences delegate) {
        this.delegate = delegate;
    }

    public static PreferencesStore global() {
        return new PreferencesStore(Preferences.userRoot().node(ROOT_NODE));
    }

    public Optional<Path> getPath(String key) {
        String value = delegate.get(key, null);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Path.of(value));
    }

    public void putPath(String key, Path path) {
        if (key == null || key.isBlank() || path == null) return;
        delegate.put(key, path.toString());
        flushQuietly();
    }

    public Optional<String> getString(String key) {
        String value = delegate.get(key, null);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    public void putString(String key, String value) {
        if (key == null || key.isBlank() || value == null) return;
        delegate.put(key, value);
        flushQuietly();
    }

    private void flushQuietly() {
        try {
            delegate.flush();
        } catch (BackingStoreException ignored) {
        }
    }
}
