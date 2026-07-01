package config;

public class DatabaseConnectionConfigTest {
  public static void main(String[] args) {
    Database.validateAndLogConnectionConfig(
        "jdbc:postgresql://aws-0-region.pooler.supabase.com:5432/postgres?sslmode=require",
        "postgres.project_ref");
    expectFailure(() -> Database.validateAndLogConnectionConfig(
        "jdbc:postgresql://aws-0-region.pooler.supabase.com:5432/postgres?sslmode=require", "postgres"),
        "bare postgres user rejected");
    expectFailure(() -> Database.validateAndLogConnectionConfig(
        "jdbc:postgresql://aws-0-region.pooler.supabase.com:5432/postgres?sslmode=require&user=postgres", "postgres.project_ref"),
        "URL user override rejected");
    Database.validateAndLogConnectionConfig("jdbc:postgresql://localhost:5433/shiftflow_dev", "shiftflow");
    System.out.println("DatabaseConnectionConfigTest: all checks passed");
  }

  private static void expectFailure(Runnable action, String label) {
    try { action.run(); }
    catch (IllegalStateException expected) { return; }
    throw new AssertionError("Failed: " + label);
  }
}
