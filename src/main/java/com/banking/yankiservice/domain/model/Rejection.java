package com.banking.yankiservice.domain.model;

import java.util.Objects;

/**
 * Why a card link or a payment was rejected, as the service that rejected it reported.
 *
 * @param code error code of that service
 * @param message its explanation
 */
public record Rejection(String code, String message) {

  private static final int MAX_CODE = 60;
  private static final int MAX_MESSAGE = 300;

  /** Keeps both texts within what is stored; a missing message becomes the code. */
  public Rejection {
    code = cut(Objects.requireNonNull(code, "code"), MAX_CODE);
    message = cut(message == null || message.isBlank() ? code : message, MAX_MESSAGE);
  }

  private static String cut(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max);
  }
}
