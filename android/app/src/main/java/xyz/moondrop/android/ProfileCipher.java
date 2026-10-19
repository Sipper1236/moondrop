package xyz.moondrop.android;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.*;
import java.security.*;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.SecretKey;

/** App-private encrypted profile storage; its non-exportable key lives in Android Keystore. */
final class ProfileCipher implements CacheCipher {
    private static final byte[] MAGIC={'M','D','P','1'};
    private static final String ALIAS="moondrop.saved-profiles.v1";
    private SecretKey key() throws GeneralSecurityException, IOException {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(ALIAS))return (SecretKey)store.getKey(ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
    @Override public OutputStream encrypt(OutputStream target) throws IOException {
        try {Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());target.write(MAGIC);target.write(cipher.getIV().length);target.write(cipher.getIV());return new CipherOutputStream(target,cipher);}
        catch(GeneralSecurityException failure){throw new IOException("Saved profiles could not be encrypted",failure);}
    }
    @Override public InputStream decrypt(InputStream source) throws IOException {
        try {
            if(!Arrays.equals(read(source,4),MAGIC))throw new IOException("Saved profiles have an invalid header");
            int length=source.read();if(length!=12)throw new IOException("Saved profiles have an invalid nonce");byte[] nonce=read(source,length);if(nonce.length!=length)throw new IOException("Saved profiles are incomplete");
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,nonce));return new CipherInputStream(source,cipher);
        }catch(GeneralSecurityException failure){throw new IOException("Saved profiles could not be unlocked",failure);}
    }
    private static byte[] read(InputStream source,int length) throws IOException {byte[] value=new byte[length];new DataInputStream(source).readFully(value);return value;}
    static boolean encrypted(File file) throws IOException {try(InputStream source=new FileInputStream(file)){return Arrays.equals(read(source,4),MAGIC);}}
}
