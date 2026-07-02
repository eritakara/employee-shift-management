package filter;

public final class TransportSecurityPolicy {
  private TransportSecurityPolicy() { }

  public static boolean shouldUseHsts(String appEnv, boolean secureRequest, String forwardedProto) {
    if (!"production".equalsIgnoreCase(appEnv)) return false;
    if (secureRequest) return true;
    if (forwardedProto == null) return false;
    String firstValue = forwardedProto.split(",", 2)[0].trim();
    return "https".equalsIgnoreCase(firstValue);
  }
}
