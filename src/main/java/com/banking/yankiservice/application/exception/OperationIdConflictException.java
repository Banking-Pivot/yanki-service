package com.banking.yankiservice.application.exception;

import java.util.UUID;

/** The operationId already identifies an operation that asked for something else. */
public class OperationIdConflictException extends RuntimeException {

  public OperationIdConflictException(UUID operationId) {
    super("operationId " + operationId + " was already used with different data.");
  }
}
