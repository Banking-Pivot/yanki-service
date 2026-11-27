package com.banking.yankiservice.application.exception;

/** No wallet has that ID or that mobile number. */
public class WalletNotFoundException extends RuntimeException {

  public WalletNotFoundException(String message) {
    super(message);
  }
}
