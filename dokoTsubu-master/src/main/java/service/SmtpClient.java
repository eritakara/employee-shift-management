package service;

import config.MailConfig;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class SmtpClient {
  private static final int TIMEOUT_MS = 5_000;

  public void send(MailConfig config, String recipient, String subject, String body) throws IOException {
    Socket socket = null;
    String stage = "connect";
    try {
      socket = connect(config);
      stage = "server-greeting";
      Session session = new Session(socket);
      session.expect(220);
      stage = "ehlo";
      session.command("EHLO shiftflow", 250);
      if ("starttls".equals(config.security())) {
        stage = "starttls";
        session.command("STARTTLS", 220);
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) factory.createSocket(socket, config.host(), config.port(), true);
        ssl.setSoTimeout(TIMEOUT_MS);
        stage = "tls-handshake";
        ssl.startHandshake();
        socket = ssl;
        session = new Session(socket);
        stage = "ehlo-after-tls";
        session.command("EHLO shiftflow", 250);
      }
      stage = "authenticate";
      if (!config.username().isBlank()) authenticate(session, config);
      stage = "mail-from";
      session.command("MAIL FROM:<" + cleanAddress(config.fromAddress()) + ">", 250);
      stage = "recipient";
      session.command("RCPT TO:<" + cleanAddress(recipient) + ">", 250, 251);
      stage = "data-command";
      session.command("DATA", 354);
      stage = "message-body";
      session.writeData(message(config, recipient, subject, body));
      stage = "message-acceptance";
      session.expect(250);
      stage = "quit";
      session.command("QUIT", 221);
    } catch (SocketTimeoutException e) {
      throw new SmtpTimeoutException(stage, e);
    } catch (IOException e) {
      throw new SmtpProtocolException(stage, e);
    } finally {
      if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }
  }

  public static class SmtpStageException extends IOException {
    private final String stage;
    private final Integer responseCode;
    private final String responseMessage;

    SmtpStageException(String message, String stage, IOException cause) {
      super(message, cause);
      this.stage = stage;
      this.responseCode = cause instanceof SmtpResponseException response ? response.code() : null;
      this.responseMessage = cause instanceof SmtpResponseException response ? response.safeMessage() : null;
    }

    public String stage() { return stage; }
    public Integer responseCode() { return responseCode; }
    public String responseMessage() { return responseMessage; }
  }

  public static final class SmtpTimeoutException extends SmtpStageException {
    SmtpTimeoutException(String stage, SocketTimeoutException cause) {
      super("SMTP timeout during " + stage, stage, cause);
    }
  }

  public static final class SmtpProtocolException extends SmtpStageException {
    SmtpProtocolException(String stage, IOException cause) {
      super("SMTP failure during " + stage, stage, cause);
    }
  }

  private Socket connect(MailConfig config) throws IOException {
    Socket socket = "smtps".equals(config.security())
        ? SSLSocketFactory.getDefault().createSocket() : new Socket();
    socket.connect(new InetSocketAddress(config.host(), config.port()), TIMEOUT_MS);
    socket.setSoTimeout(TIMEOUT_MS);
    if (socket instanceof SSLSocket ssl) ssl.startHandshake();
    return socket;
  }

  private void authenticate(Session session, MailConfig config) throws IOException {
    session.command("AUTH LOGIN", 334);
    session.command(Base64.getEncoder().encodeToString(config.username().getBytes(StandardCharsets.UTF_8)), 334);
    session.command(Base64.getEncoder().encodeToString(config.password().getBytes(StandardCharsets.UTF_8)), 235);
  }

  String message(MailConfig config, String recipient, String subject, String body) {
    return "Date: " + DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now()) + "\r\n"
        + "From: " + encoded(config.fromName()) + " <" + cleanAddress(config.fromAddress()) + ">\r\n"
        + "To: <" + cleanAddress(recipient) + ">\r\n"
        + "Subject: " + encoded(subject) + "\r\n"
        + "MIME-Version: 1.0\r\n"
        + "Content-Type: text/plain; charset=UTF-8\r\n"
        + "Content-Transfer-Encoding: base64\r\n"
        + "\r\n" + Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII))
            .encodeToString(body.getBytes(StandardCharsets.UTF_8)) + "\r\n";
  }

  private String encoded(String value) {
    return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "?=";
  }

  private String cleanAddress(String value) {
    if (value == null || !value.matches("^[^\\s@<>]+@[^\\s@<>]+$")) throw new IllegalArgumentException("Invalid email address");
    return value;
  }

  private static final class Session {
    private final BufferedReader reader;
    private final BufferedWriter writer;

    Session(Socket socket) throws IOException {
      reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
      writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
    }

    void command(String command, int... expected) throws IOException {
      writer.write(command); writer.write("\r\n"); writer.flush();
      expect(expected);
    }

    void writeData(String data) throws IOException {
      writer.write(formatData(data)); writer.flush();
    }

    void expect(int... expected) throws IOException {
      String line;
      int code = -1;
      do {
        line = reader.readLine();
        if (line == null) throw new IOException("SMTP server closed the connection");
        if (line.length() >= 3) {
          try { code = Integer.parseInt(line.substring(0, 3)); } catch (NumberFormatException ignored) { }
        }
      } while (line.length() > 3 && line.charAt(3) == '-');
      for (int value : expected) if (code == value) return;
      throw new SmtpResponseException(code, line);
    }
  }

  static String formatData(String data) {
    String normalized = data.replace("\r\n", "\n").replace('\r', '\n');
    String[] lines = normalized.split("\n", -1);
    int count = lines.length;
    if (count > 1 && lines[count - 1].isEmpty()) count--;
    StringBuilder formatted = new StringBuilder(normalized.length() + 16);
    for (int i = 0; i < count; i++) {
      if (lines[i].startsWith(".")) formatted.append('.');
      formatted.append(lines[i]).append("\r\n");
    }
    return formatted.append(".\r\n").toString();
  }

  private static final class SmtpResponseException extends IOException {
    private final int code;
    private final String safeMessage;

    SmtpResponseException(int code, String responseLine) {
      super("SMTP server rejected the request with response code " + code);
      this.code = code;
      this.safeMessage = sanitizeResponse(responseLine);
    }

    int code() { return code; }
    String safeMessage() { return safeMessage; }
  }

  private static String sanitizeResponse(String value) {
    if (value == null) return "unknown";
    String sanitized = value.replaceAll("[\\r\\n\\p{Cntrl}]", " ")
        .replaceAll("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", "[email]")
        .replaceAll("(?i)https?://\\S+", "[url]")
        .replaceAll("(?i)\\b(?:re_[A-Z0-9_=-]+|bearer\\s+\\S+)\\b", "[redacted]")
        .replaceAll("(?i)\\b(?:token|password|api[_ -]?key)\\s*[=:]\\s*\\S+", "[redacted]")
        .replaceAll("(?i)\\b[A-F0-9]{24,}\\b", "[redacted]");
    return sanitized.substring(0, Math.min(sanitized.length(), 500));
  }
}
