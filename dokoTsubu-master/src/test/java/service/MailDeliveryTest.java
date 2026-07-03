package service;

import config.Database;
import config.MailConfig;
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
    System.clearProperty("SMTP_SECURITY");
    System.clearProperty("SHIFTFLOW_SMTP_SECURITY");
    System.setProperty("SMTP_PORT", "465");
    check("smtps".equals(config.MailConfig.load().security()), "port 465 selects implicit TLS");
    System.setProperty("SMTP_PORT", "587");
    check("starttls".equals(config.MailConfig.load().security()), "port 587 selects STARTTLS");
    try (FakeSmtpServer smtp = new FakeSmtpServer()) {
      System.setProperty("SMTP_HOST", "127.0.0.1");
      System.setProperty("SMTP_PORT", String.valueOf(smtp.port()));
      System.setProperty("SHIFTFLOW_SMTP_SECURITY", "plain");
      System.setProperty("SMTP_FROM", "onboarding@resend.dev");
      System.setProperty("SHIFTFLOW_MAIL_MAX_ATTEMPTS", "3");
      long id = Sql.insert("INSERT INTO mail_outbox(recipient,subject,body) VALUES(?,?,?)",
          "employee@example.test", "勤務通知", "シフトが確定しました。");
      boolean delivered = new MailDeliveryService().deliverNow(id);
      check(delivered, "selected message delivered immediately");
      check("SENT".equals(Sql.one("SELECT status FROM mail_outbox WHERE id=?", id).get("status")), "outbox marked sent");
      check(smtp.message().contains("Content-Type: text/plain; charset=UTF-8"), "UTF-8 MIME message");
      check("MAIL FROM:<onboarding@resend.dev>".equals(smtp.mailFrom()), "SMTP_FROM is used as envelope sender");
    }

    MailConfig formatConfig = new MailConfig("smtp.example.test", 587, "plain", "", "",
        "onboarding@resend.dev", "\u30b7\u30d5\u30c8\u7ba1\u7406", 3);
    String mime = new SmtpClient().message(formatConfig, "employee@example.test",
        "\u62db\u5f85\u30e1\u30fc\u30eb", "\u672c\u6587");
    check(mime.contains("From: =?UTF-8?B?"), "From display name is MIME encoded");
    check(mime.contains(" <onboarding@resend.dev>\r\n"), "SMTP_FROM is used in the From header");
    check(mime.contains("To: <employee@example.test>\r\n"), "To header uses angle brackets");
    check(mime.contains("Subject: =?UTF-8?B?"), "Japanese subject is MIME encoded");
    check(!mime.replace("\r\n", "").contains("\n"), "MIME message uses CRLF line endings");
    check("..first\r\nsecond\r\n.\r\n".equals(SmtpClient.formatData(".first\nsecond\r\n")),
        "SMTP DATA uses dot escaping, CRLF, and a single terminator");

    check("250 Message accepted".equals(SmtpClient.sanitizeResponse("250 Message accepted")),
        "normal SMTP response remains readable");
    String controlled = SmtpClient.sanitizeResponse("550 rejected\r\n\u0000owner@example.com token=secret-value");
    check(controlled.indexOf('\r') < 0 && controlled.indexOf('\n') < 0 && controlled.indexOf('\u0000') < 0,
        "SMTP response control characters are removed");
    check(!controlled.contains("owner@example.com") && !controlled.contains("secret-value"),
        "SMTP response credentials are redacted");
    check(!SmtpClient.sanitizeResponse("550 password = another-secret").contains("another-secret"),
        "spaced SMTP response credentials are redacted");
    String longResponse = SmtpClient.sanitizeResponse("%".repeat(100_000));
    check(longResponse.length() == 500, "long SMTP response is bounded safely");

    String secretToken = "0123456789abcdef0123456789abcdef";
    try (FakeSmtpServer smtp = new FakeSmtpServer(
        "550 You can only send testing emails to owner@example.com; token=" + secretToken)) {
      MailConfig rejectedConfig = new MailConfig("127.0.0.1", smtp.port(), "plain", "", "",
          "noreply@example.test", "ShiftFlow", 3);
      try {
        new SmtpClient().send(rejectedConfig, "employee@example.test", "invitation", "body");
        throw new AssertionError("Failed: SMTP rejection must throw");
      } catch (SmtpClient.SmtpProtocolException e) {
        check("message-acceptance".equals(e.stage()), "SMTP rejection stage is retained");
        check(Integer.valueOf(550).equals(e.responseCode()), "SMTP response code is retained safely");
        check(e.responseMessage().contains("You can only send testing emails"), "safe SMTP response text is retained");
        check(!e.responseMessage().contains("owner@example.com"), "email in SMTP response is masked");
        check(!e.responseMessage().contains(secretToken), "token in SMTP response is masked");
        check(!MailDeliveryService.smtpLogDetails(e, true).contains("smtpMessage="),
            "production SMTP log omits response details");
        check(MailDeliveryService.smtpLogDetails(e, false).contains("smtpMessage="),
            "development SMTP log retains sanitized response details");
      }
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
    private final String dataResponse;
    private volatile String message = "";
    private volatile String mailFrom = "";

    FakeSmtpServer() throws Exception {
      this("250 queued");
    }

    FakeSmtpServer(String dataResponse) throws Exception {
      this.dataResponse = dataResponse;
      server = new ServerSocket(0);
      worker = new Thread(this::serve, "fake-smtp");
      worker.start();
    }

    int port() { return server.getLocalPort(); }
    String message() throws InterruptedException { worker.join(5_000); return message; }
    String mailFrom() { return mailFrom; }

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
            if (".".equals(line)) {
              data = false;
              message = captured.toString();
              send(writer, dataResponse);
            }
            else captured.append(line).append('\n');
          } else if (line.startsWith("EHLO")) send(writer, "250 fake-smtp");
          else if (line.startsWith("MAIL FROM")) { mailFrom = line; send(writer, "250 ok"); }
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
