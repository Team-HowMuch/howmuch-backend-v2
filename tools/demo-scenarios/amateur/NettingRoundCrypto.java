import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Operator fixture helper. Plain account numbers never leave this JVM. */
public class NettingRoundCrypto {
    private static final Base64.Decoder DECODER = Base64.getDecoder();
    private static final Base64.Encoder ENCODER = Base64.getEncoder();
    private static final SecureRandom RANDOM = new SecureRandom();

    private static Cipher cipher(int mode, byte[] key, byte[] nonce, String aad) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private static String encrypt(byte[] key, String aad, byte[] plaintext) throws Exception {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plaintext);
        // Decrypt the newly generated value as part of every operation, including dry runs.
        byte[] checked = cipher(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(encrypted);
        if (!java.security.MessageDigest.isEqual(plaintext, checked)) {
            throw new IllegalStateException("Cipher round trip failed");
        }
        return "v2:" + ENCODER.encodeToString(ByteBuffer.allocate(12 + encrypted.length)
                .put(nonce).put(encrypted).array());
    }

    public static void main(String[] args) throws Exception {
        byte[] key = DECODER.decode(System.getenv("CODEF_CONNECTED_ID_ENCRYPTION_KEY"));
        if (key.length != 16 && key.length != 24 && key.length != 32) {
            throw new IllegalArgumentException("Invalid runtime key size");
        }
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            for (String line; (line = input.readLine()) != null;) {
                String[] fields = line.split("\t", -1);
                String aad = new String(DECODER.decode(fields[1]), StandardCharsets.UTF_8);
                if (fields[0].equals("REKEY") && fields.length == 4) {
                    String old = new String(DECODER.decode(fields[3]), StandardCharsets.UTF_8);
                    if (!old.startsWith("v2:")) throw new IllegalArgumentException("Expected v2 snapshot");
                    byte[] value = DECODER.decode(old.substring(3));
                    byte[] nonce = java.util.Arrays.copyOfRange(value, 0, 12);
                    byte[] plaintext = cipher(Cipher.DECRYPT_MODE, key, nonce, aad)
                            .doFinal(java.util.Arrays.copyOfRange(value, 12, value.length));
                    String newAad = new String(DECODER.decode(fields[2]), StandardCharsets.UTF_8);
                    System.out.println(encrypt(key, newAad, plaintext));
                } else if (fields[0].equals("ENC") && fields.length == 3) {
                    System.out.println(encrypt(key, aad, DECODER.decode(fields[2])));
                } else {
                    throw new IllegalArgumentException("Invalid operation");
                }
            }
        }
    }
}
