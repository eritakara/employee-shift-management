package service;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RequestRateLimiter {
  private record Window(int count, long startedAt, long lastSeenAt) { }

  private final int limit;
  private final long windowMillis;
  private final int maxEntries;
  private final LinkedHashMap<String, Window> windows = new LinkedHashMap<>();

  public RequestRateLimiter(int limit, long windowMillis, int maxEntries) {
    if (limit < 1 || windowMillis < 1 || maxEntries < 1) throw new IllegalArgumentException("Invalid rate limit configuration");
    this.limit = limit;
    this.windowMillis = windowMillis;
    this.maxEntries = maxEntries;
  }

  public synchronized boolean isBlocked(String key, long now) {
    cleanup(now);
    Window window = windows.get(normalize(key));
    return window != null && now - window.startedAt() < windowMillis && window.count() >= limit;
  }

  public synchronized void record(String key, long now) {
    cleanup(now);
    String normalized = normalize(key);
    Window current = windows.get(normalized);
    if (current == null || now - current.startedAt() >= windowMillis) {
      ensureCapacity();
      windows.put(normalized, new Window(1, now, now));
    } else {
      windows.put(normalized, new Window(current.count() + 1, current.startedAt(), now));
    }
  }

  public synchronized void clear(String key) {
    windows.remove(normalize(key));
  }

  synchronized int size() { return windows.size(); }

  private void cleanup(long now) {
    windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt() >= windowMillis);
  }

  private void ensureCapacity() {
    while (windows.size() >= maxEntries) {
      Iterator<Map.Entry<String, Window>> iterator = windows.entrySet().iterator();
      Map.Entry<String, Window> oldest = null;
      while (iterator.hasNext()) {
        Map.Entry<String, Window> candidate = iterator.next();
        if (oldest == null || candidate.getValue().lastSeenAt() < oldest.getValue().lastSeenAt()) oldest = candidate;
      }
      if (oldest == null) return;
      windows.remove(oldest.getKey());
    }
  }

  private String normalize(String key) {
    return key == null ? "" : key;
  }
}
