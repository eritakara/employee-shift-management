package config;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import service.ScheduledTasks;

@WebListener
public class AppBootstrap implements ServletContextListener {
  private ScheduledExecutorService scheduler;

  @Override
  public void contextInitialized(ServletContextEvent event) {
    Database.initialize();
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
      catch (RuntimeException e) { event.getServletContext().log("Daily task failed", e); }
    }, initialDelay, TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS);
  }

  @Override
  public void contextDestroyed(ServletContextEvent event) {
    if (scheduler != null) scheduler.shutdownNow();
  }
}
