package service;

import config.MailConfig;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class SmtpClient {
  private static final int TIMEOUT_MS = 15_000;

  public void send(MailConfig config, String recipient, String subject, String body) throws IOException {
    Socket socket = connect(config);
    try {
      Session session = new Session(socket);
      session.expect(220);
      session.command("EHLO shiftflow", 250);
      if ("starttls".equals(config.security())) {
        session.command("STARTTLS", 220);
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) factory.createSocket(socket, config.host(), config.port(), true);
        ssl.setSoTimeout(TIMEOUT_MS);
        ssl.startHandshake();
        socket = ssl;
        session = new Session(socket);
        session.command("EHLO shiftflow", 250);
      }
      if (!config.username().isBlank()) authenticate(session, config);
      session.command("MAIL FROM:<" + cleanAddress(config.fromAddress()) + ">", 250);
      session.command("RCPT TO:<" + cleanAddress(recipient) + ">", 250, 251);
      session.command("DATA", 354);
      session.writeData(message(config, recipient, subject, body));
      session.expect(250);
      session.command("QUIT", 221);
    } finally {
      try { socket.close(); } catch (IOException ignored) { }
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

  private String message(MailConfig config, String recipient, String subject, String body) {
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
      for (String line : data.replace("\r\n", "\n").split("\n", -1)) {
        if (line.startsWith(".")) writer.write('.');
        writer.write(line); writer.write("\r\n");
      }
      writer.write(".\r\n"); writer.flush();
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
      throw new IOException("SMTP error " + code + ": " + sanitize(line));
    }

    private String sanitize(String value) {
      if (value == null) return "unknown";
      String sanitized = value.replaceAll("[\\r\\n]", " ");
      return sanitized.substring(0, Math.min(sanitized.length(), 500));
    }
  }
}
