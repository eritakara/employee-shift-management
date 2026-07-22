package config;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import util.SecurityLog;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import service.ScheduledTasks;
import service.MailDeliveryService;

@WebListener
public class AppBootstrap implements ServletContextListener {
  private ScheduledExecutorService scheduler;

  @Override
  public void contextInitialized(ServletContextEvent event) {
    long bootstrapStarted = System.nanoTime();
    try {
      event.getServletContext().log("AppBootstrap: Initializing database...");
      Database.initialize();
      event.getServletContext().log("AppBootstrap: Database initialized successfully.");
    } catch (Throwable t) {
      SecurityLog.error("Application bootstrap initialization failed", t);
      throw t;
    }

    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread thread = new Thread(r, "shiftflow-daily-tasks");
      thread.setDaemon(true);
      return thread;
    });
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime next = now.toLocalDate().plusDays(1).atTime(0, 5);
    long initialDelay = Duration.between(now, next).toSeconds();
    scheduler.scheduleAtFixedRate(() -> {
      try { new ScheduledTasks().runDaily(); }
      catch (RuntimeException e) { SecurityLog.error("Daily scheduled task failed", e); }
    }, initialDelay, TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS);
    scheduler.scheduleWithFixedDelay(() -> {
      try { new MailDeliveryService().deliverPending(); }
      catch (RuntimeException e) { SecurityLog.error("Scheduled mail delivery failed", e); }
    }, 10, 60, TimeUnit.SECONDS);
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - bootstrapStarted);
    event.getServletContext().log("Startup timing: application.bootstrap=" + elapsedMillis + " ms");
  }

  @Override
  public void contextDestroyed(ServletContextEvent event) {
    if (scheduler != null) scheduler.shutdownNow();
  }
}
