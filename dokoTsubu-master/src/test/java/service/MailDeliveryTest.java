package service;

import config.Database;
import dao.Sql;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class MailDeliveryTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-mail-test-").toString());
    Database.initialize();
    try (FakeSmtpServer smtp = new FakeSmtpServer()) {
      System.setProperty("SMTP_HOST", "127.0.0.1");
      System.setProperty("SMTP_PORT", String.valueOf(smtp.port()));
      System.setProperty("SHIFTFLOW_SMTP_SECURITY", "plain");
      System.setProperty("SMTP_FROM", "noreply@example.test");
      System.setProperty("SHIFTFLOW_MAIL_MAX_ATTEMPTS", "3");
      long id = Sql.insert("INSERT INTO mail_outbox(recipient,subject,body) VALUES(?,?,?)",
          "employee@example.test", "勤務通知", "シフトが確定しました。");
      boolean delivered = new MailDeliveryService().deliverNow(id);
      check(delivered, "selected message delivered immediately");
      check("SENT".equals(Sql.one("SELECT status FROM mail_outbox WHERE id=?", id).get("status")), "outbox marked sent");
      check(smtp.message().contains("Content-Type: text/plain; charset=UTF-8"), "UTF-8 MIME message");
    }

    System.setProperty("SMTP_PORT", "1");
    System.setProperty("SHIFTFLOW_MAIL_MAX_ATTEMPTS", "1");
    long failedId = Sql.insert("INSERT INTO mail_outbox(recipient,subject,body) VALUES(?,?,?)",
        "employee@example.test", "failure", "failure test");
    new MailDeliveryService().deliverPending();
    check("FAILED".equals(Sql.one("SELECT status FROM mail_outbox WHERE id=?", failedId).get("status")), "failure marked after max attempts");
    System.out.println("MailDeliveryTest: all checks passed");
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }

  private static final class FakeSmtpServer implements AutoCloseable {
    private final ServerSocket server;
    private final Thread worker;
    private volatile String message = "";

    FakeSmtpServer() throws Exception {
      server = new ServerSocket(0);
      worker = new Thread(this::serve, "fake-smtp");
      worker.start();
    }

    int port() { return server.getLocalPort(); }
    String message() throws InterruptedException { worker.join(5_000); return message; }

    private void serve() {
      try (Socket socket = server.accept();
           BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
           BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII))) {
        send(writer, "220 fake-smtp");
        boolean data = false;
        StringBuilder captured = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
          if (data) {
            if (".".equals(line)) { data = false; message = captured.toString(); send(writer, "250 queued"); }
            else captured.append(line).append('\n');
          } else if (line.startsWith("EHLO")) send(writer, "250 fake-smtp");
          else if (line.startsWith("MAIL FROM")) send(writer, "250 ok");
          else if (line.startsWith("RCPT TO")) send(writer, "250 ok");
          else if ("DATA".equals(line)) { data = true; send(writer, "354 send data"); }
          else if ("QUIT".equals(line)) { send(writer, "221 bye"); break; }
          else send(writer, "250 ok");
        }
      } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void send(BufferedWriter writer, String line) throws Exception {
      writer.write(line); writer.write("\r\n"); writer.flush();
    }

    @Override public void close() throws Exception { server.close(); worker.join(5_000); }
  }
}
