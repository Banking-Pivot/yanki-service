package com.banking.yankiservice.application.exception;

/** The identity document already has a wallet. */
public class DocumentAlreadyRegisteredException extends RuntimeException {

  public DocumentAlreadyRegisteredException(Throwable cause) {
    super("The identity document already has a wallet.", cause);
  }
}
