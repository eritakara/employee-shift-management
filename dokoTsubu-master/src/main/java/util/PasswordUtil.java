package util;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class PasswordUtil {
  private static final int ITERATIONS = 120_000;
  private static final int KEY_LENGTH = 256;

  private PasswordUtil() { }

  public static String hash(String password) {
    byte[] salt = new byte[16];
    new SecureRandom().nextBytes(salt);
    return Base64.getEncoder().encodeToString(salt) + ":" +
        Base64.getEncoder().encodeToString(derive(password, salt));
  }

  public static boolean verify(String password, String stored) {
    if (password == null || stored == null || !stored.contains(":")) return false;
    String[] parts = stored.split(":", 2);
    byte[] salt = Base64.getDecoder().decode(parts[0]);
    byte[] expected = Base64.getDecoder().decode(parts[1]);
    byte[] actual = derive(password, salt);
    if (expected.length != actual.length) return false;
    int difference = 0;
    for (int i = 0; i < expected.length; i++) difference |= expected[i] ^ actual[i];
    return difference == 0;
  }

  private static byte[] derive(String password, byte[] salt) {
    try {
      PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Password hashing is unavailable", e);
    }
  }
}
