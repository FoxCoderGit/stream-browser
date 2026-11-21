package dev.stream.browser;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

final class SecureStore {
    private static synchronized SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (!ks.containsAlias("stream-jobs")) {
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder("stream-jobs",
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) ks.getKey("stream-jobs", null);
    }
    static File dir(Context c) { File d = new File(c.getNoBackupFilesDir(), "jobs"); d.mkdirs(); return d; }
    static synchronized void save(Context c, String id, JSONObject data) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        AtomicFile f = new AtomicFile(new File(dir(c), id + ".bin"));
        FileOutputStream out = f.startWrite();
        try {
            out.write(cipher.getIV()); out.write(cipher.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)));
            f.finishWrite(out);
        } catch (Exception e) { f.failWrite(out); throw e; }
    }
    static synchronized JSONObject read(Context c, String id) throws Exception {
        byte[] data = new AtomicFile(new File(dir(c), id + ".bin")).readFully();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOf(data, 12)));
        return new JSONObject(new String(cipher.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8));
    }
}
