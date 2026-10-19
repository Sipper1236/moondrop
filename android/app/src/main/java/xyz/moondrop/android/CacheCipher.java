package xyz.moondrop.android;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Storage encryption boundary, also allowing deterministic cache migration tests. */
interface CacheCipher {
    OutputStream encrypt(OutputStream target) throws IOException;
    InputStream decrypt(InputStream source) throws IOException;
}
