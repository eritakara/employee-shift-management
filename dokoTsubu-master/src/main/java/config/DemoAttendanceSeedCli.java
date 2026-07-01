package config;

/** One-shot entry point for applying the attendance demo to a configured database. */
public final class DemoAttendanceSeedCli {
  private DemoAttendanceSeedCli() { }

  public static void main(String[] args) {
    Database.initialize();
    System.out.println("DemoAttendanceSeedCli finished. Remove the reset environment variables before starting the web app.");
  }
}
