package com.checkout.payment.gateway.logging;

/**
 * Prepares caller supplied text for a log line. A value that reaches the logs unaltered can carry
 * newlines, and so forge log entries of its own.
 */
public final class LogSafe {

  private static final int MAX_LENGTH = 64;
  private static final String CONTROL_CHARACTERS = "\\p{Cntrl}";
  private static final String REPLACEMENT = "?";

  private LogSafe() {
  }

  public static String text(String value) {
    if (value == null) {
      return "null";
    }
    String sanitised = value.replaceAll(CONTROL_CHARACTERS, REPLACEMENT);
    return sanitised.length() > MAX_LENGTH
        ? sanitised.substring(0, MAX_LENGTH) + "..."
        : sanitised;
  }
}
