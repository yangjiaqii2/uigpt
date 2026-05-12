package top.uigpt.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-256-GCM：用于 chat_models.api_key_cipher 的加密/解密（依赖同一主密钥）。 */
public final class ModelKeyCryptoUtil {

    private static final String AES = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private ModelKeyCryptoUtil() {}

    public static byte[] deriveKeyBytes(String masterSecret) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(masterSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 生成写入数据库的 Base64 密文（含 IV）。 */
    public static String encrypt(String masterSecret, String plaintext) {
        if (masterSecret == null || masterSecret.isBlank()) {
            throw new IllegalArgumentException("主密钥不能为空");
        }
        if (plaintext == null) {
            throw new IllegalArgumentException("明文不能为空");
        }
        try {
            byte[] keyBytes = deriveKeyBytes(masterSecret);
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(keyBytes, AES),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(iv.length + ct.length);
            buf.put(iv);
            buf.put(ct);
            return Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    public static String decrypt(String masterSecret, String ciphertextBase64) {
        if (masterSecret == null || masterSecret.isBlank()) {
            throw new IllegalArgumentException("主密钥不能为空");
        }
        if (ciphertextBase64 == null || ciphertextBase64.isBlank()) {
            throw new IllegalArgumentException("密文不能为空");
        }
        try {
            byte[] all = Base64.getDecoder().decode(ciphertextBase64.trim());
            if (all.length < IV_LENGTH + 1) {
                throw new IllegalArgumentException("密文格式无效");
            }
            ByteBuffer buf = ByteBuffer.wrap(all);
            byte[] iv = new byte[IV_LENGTH];
            buf.get(iv);
            byte[] ct = new byte[buf.remaining()];
            buf.get(ct);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(deriveKeyBytes(masterSecret), AES),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(ct);
            return new String(plain, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("解密失败（请核对 UIGPT_MODEL_KEY_MASTER 是否与加密时一致）", e);
        }
    }
}
