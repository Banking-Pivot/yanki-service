package com.banking.yankiservice.domain.model.enums;

/** Whether a wallet can still be used. */
public enum WalletStatus {
  /** The wallet can be topped up, linked to a card, pay and be paid. */
  ACTIVE,
  /** The wallet was closed by its owner. It can only be read, and it never opens again. */
  CLOSED
}
