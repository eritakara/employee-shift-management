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
  private static final int MAX_RESPONSE_INPUT_LENGTH = 2_000;
  private static final int MAX_SAFE_RESPONSE_LENGTH = 500;

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

  static String sanitizeResponse(String value) {
    if (value == null) return "unknown";
    int inputLength = Math.min(value.length(), MAX_RESPONSE_INPUT_LENGTH);
    StringBuilder normalized = new StringBuilder(inputLength);
    for (int i = 0; i < inputLength; i++) {
      char ch = value.charAt(i);
      normalized.append(Character.isISOControl(ch) ? ' ' : ch);
    }

    StringBuilder safe = new StringBuilder(Math.min(inputLength, MAX_SAFE_RESPONSE_LENGTH));
    boolean redactNextToken = false;
    int index = 0;
    while (index < normalized.length() && safe.length() < MAX_SAFE_RESPONSE_LENGTH) {
      if (Character.isWhitespace(normalized.charAt(index))) {
        appendLimited(safe, ' ');
        index++;
        continue;
      }

      int tokenEnd = index + 1;
      while (tokenEnd < normalized.length() && !Character.isWhitespace(normalized.charAt(tokenEnd))) {
        tokenEnd++;
      }
      String token = normalized.substring(index, tokenEnd);
      boolean bearer = equalsIgnoreCase(token, "bearer");
      boolean sensitiveLabel = isSensitiveLabel(token);
      boolean assignmentSeparator = token.equals("=") || token.equals(":");
      if (redactNextToken || bearer || shouldRedactToken(token)) {
        appendLimited(safe, "[redacted]");
      } else {
        appendLimited(safe, token);
      }
      redactNextToken = bearer || sensitiveLabel || (redactNextToken && assignmentSeparator);
      index = tokenEnd;
    }
    return safe.toString();
  }

  private static boolean shouldRedactToken(String token) {
    return containsEmailAddress(token)
        || startsWithIgnoreCase(trimLeadingPunctuation(token), "http://")
        || startsWithIgnoreCase(trimLeadingPunctuation(token), "https://")
        || startsWithIgnoreCase(trimLeadingPunctuation(token), "re_")
        || containsSecretAssignment(token)
        || isLongHexToken(token);
  }

  private static boolean containsEmailAddress(String token) {
    int at = token.indexOf('@');
    return at > 0 && token.indexOf('.', at + 2) > at + 1;
  }

  private static boolean containsSecretAssignment(String token) {
    String lower = token.toLowerCase(java.util.Locale.ROOT);
    return hasAssignment(lower, "token")
        || hasAssignment(lower, "password")
        || hasAssignment(lower, "api_key")
        || hasAssignment(lower, "api-key")
        || hasAssignment(lower, "apikey");
  }

  private static boolean hasAssignment(String value, String name) {
    int start = value.indexOf(name);
    if (start < 0) return false;
    int separator = start + name.length();
    return separator < value.length()
        && (value.charAt(separator) == '=' || value.charAt(separator) == ':');
  }

  private static boolean isSensitiveLabel(String token) {
    String lower = trimPunctuation(token).toLowerCase(java.util.Locale.ROOT);
    return lower.equals("token") || lower.equals("password")
        || lower.equals("api_key") || lower.equals("api-key") || lower.equals("apikey");
  }

  private static boolean isLongHexToken(String token) {
    String core = trimPunctuation(token);
    if (core.length() < 24) return false;
    for (int i = 0; i < core.length(); i++) {
      char ch = core.charAt(i);
      if (!((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F'))) {
        return false;
      }
    }
    return true;
  }

  private static String trimLeadingPunctuation(String value) {
    int start = 0;
    while (start < value.length() && !Character.isLetterOrDigit(value.charAt(start))) start++;
    return value.substring(start);
  }

  private static String trimPunctuation(String value) {
    int start = 0;
    int end = value.length();
    while (start < end && !Character.isLetterOrDigit(value.charAt(start))) start++;
    while (end > start && !Character.isLetterOrDigit(value.charAt(end - 1))) end--;
    return value.substring(start, end);
  }

  private static boolean startsWithIgnoreCase(String value, String prefix) {
    return value.length() >= prefix.length() && value.regionMatches(true, 0, prefix, 0, prefix.length());
  }

  private static boolean equalsIgnoreCase(String value, String expected) {
    return value.length() == expected.length() && value.regionMatches(true, 0, expected, 0, expected.length());
  }

  private static void appendLimited(StringBuilder target, char value) {
    if (target.length() < MAX_SAFE_RESPONSE_LENGTH) target.append(value);
  }

  private static void appendLimited(StringBuilder target, String value) {
    int remaining = MAX_SAFE_RESPONSE_LENGTH - target.length();
    if (remaining <= 0) return;
    target.append(value, 0, Math.min(value.length(), remaining));
  }
}
