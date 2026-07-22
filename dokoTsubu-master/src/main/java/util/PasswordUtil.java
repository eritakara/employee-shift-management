package util;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class PasswordUtil {
  private static final String ALGORITHM = "pbkdf2-sha256";
  private static final int LEGACY_ITERATIONS = 120_000;
  private static final int CURRENT_ITERATIONS = 600_000;
  private static final int MAX_STORED_ITERATIONS = 2_000_000;
  private static final int KEY_LENGTH = 256;

  private PasswordUtil() { }

  public static String hash(String password) {
    if (password == null) throw new IllegalArgumentException("Password is required");
    byte[] salt = new byte[16];
    new SecureRandom().nextBytes(salt);
    return ALGORITHM + "$" + CURRENT_ITERATIONS + "$"
        + Base64.getEncoder().encodeToString(salt) + "$"
        + Base64.getEncoder().encodeToString(derive(password, salt, CURRENT_ITERATIONS));
  }

  public static boolean verify(String password, String stored) {
    if (password == null || stored == null) return false;
    try {
      if (stored.startsWith(ALGORITHM + "$")) {
        String[] parts = stored.split("\\$", -1);
        if (parts.length != 4) return false;
        int iterations = Integer.parseInt(parts[1]);
        if (iterations < LEGACY_ITERATIONS || iterations > MAX_STORED_ITERATIONS) return false;
        return matches(password, parts[2], parts[3], iterations);
      }
      String[] legacy = stored.split(":", -1);
      return legacy.length == 2 && matches(password, legacy[0], legacy[1], LEGACY_ITERATIONS);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  public static boolean needsRehash(String stored) {
    if (stored == null || !stored.startsWith(ALGORITHM + "$")) return true;
    try {
      String[] parts = stored.split("\\$", -1);
      return parts.length != 4 || Integer.parseInt(parts[1]) < CURRENT_ITERATIONS;
    } catch (NumberFormatException e) {
      return true;
    }
  }

  private static boolean matches(String password, String encodedSalt, String encodedHash, int iterations) {
    byte[] salt = Base64.getDecoder().decode(encodedSalt);
    byte[] expected = Base64.getDecoder().decode(encodedHash);
    byte[] actual = derive(password, salt, iterations);
    return MessageDigest.isEqual(expected, actual);
  }

  private static byte[] derive(String password, byte[] salt, int iterations) {
    try {
      PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH);
      try {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
      } finally {
        spec.clearPassword();
      }
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Password hashing is unavailable", e);
    }
  }
}
