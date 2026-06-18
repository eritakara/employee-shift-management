package service;

import config.Database;
import dao.UserDAO;
import java.nio.file.Files;
import java.time.YearMonth;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import model.User;

public class ConcurrentLoadTest {
  private static final int CONCURRENT_USERS = 100;

  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-concurrent-load-").toString());
    Database.initialize();
    User employee = new UserDAO().authenticate("employee@example.com", "Password1!");
    ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_USERS);
    CountDownLatch ready = new CountDownLatch(CONCURRENT_USERS);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(CONCURRENT_USERS);
    Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
    AtomicLong slowestMillis = new AtomicLong();

    for (int i = 0; i < CONCURRENT_USERS; i++) {
      pool.submit(() -> {
        ready.countDown();
        try {
          if (!start.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("load start timed out");
          long started = System.nanoTime();
          PortalService portal = new PortalService();
          if (portal.dashboard(employee).isEmpty()) throw new AssertionError("empty dashboard");
          portal.shifts(employee, YearMonth.now());
          slowestMillis.accumulateAndGet((System.nanoTime() - started) / 1_000_000, Math::max);
        } catch (Throwable failure) {
          failures.add(failure);
        } finally {
          done.countDown();
        }
      });
    }

    check(ready.await(15, TimeUnit.SECONDS), "100 simulated users became ready");
    long loadStarted = System.nanoTime();
    start.countDown();
    check(done.await(60, TimeUnit.SECONDS), "100 concurrent operations completed within 60 seconds");
    pool.shutdownNow();
    check(failures.isEmpty(), failures.isEmpty() ? "no concurrent failures" : "concurrent failure: " + failures.peek());
    long totalMillis = (System.nanoTime() - loadStarted) / 1_000_000;
    System.out.println("ConcurrentLoadTest: users=100 total=" + totalMillis + " ms slowest=" + slowestMillis.get() + " ms");
    System.out.println("ConcurrentLoadTest: all checks passed");
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}

