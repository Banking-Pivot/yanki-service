package com.banking.yankiservice.application.exception;

/** The mobile number already has a wallet. */
public class PhoneAlreadyRegisteredException extends RuntimeException {

  public PhoneAlreadyRegisteredException(Throwable cause) {
    super("The mobile number already has a wallet.", cause);
  }
}
