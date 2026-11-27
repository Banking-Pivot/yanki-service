package com.banking.yankiservice.application.exception;

import java.util.UUID;

/** A wallet or a payment changed between the moment it was read and the moment it was written. */
public class ConcurrentChangeException extends RuntimeException {

  public ConcurrentChangeException(String what, UUID id) {
    super(what + " " + id + " changed while it was being updated.");
  }
}
