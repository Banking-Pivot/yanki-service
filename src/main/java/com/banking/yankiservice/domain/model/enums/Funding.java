package com.banking.yankiservice.domain.model.enums;

/** Where the money of one side of a payment is. */
public enum Funding {
  /** The balance of the wallet, kept by this service. */
  WALLET,
  /** The main account of the debit card the wallet is linked to, kept by the bank. */
  CARD
}
