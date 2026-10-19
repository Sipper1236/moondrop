package xyz.shadowmere.desktop;

import java.io.IOException;

/** Stores only the 32-byte catalog encryption key, never an entire proxy profile. */
public interface SecretStore {
    /** Returns null when no key exists; locked/unavailable storage must throw. */
    byte[] load() throws IOException;
    void save(byte[] key) throws IOException;
    void erase() throws IOException;
}
