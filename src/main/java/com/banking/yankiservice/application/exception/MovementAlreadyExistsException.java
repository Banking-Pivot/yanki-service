package com.banking.yankiservice.application.exception;

import java.util.UUID;

/** The wallet already has that movement of that operation. */
public class MovementAlreadyExistsException extends RuntimeException {

  public MovementAlreadyExistsException(UUID operationId, Throwable cause) {
    super("The movement of operation " + operationId + " already exists.", cause);
  }
}
