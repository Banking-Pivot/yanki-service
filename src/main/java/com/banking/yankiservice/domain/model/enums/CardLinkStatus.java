package com.banking.yankiservice.domain.model.enums;

/** How far the link of a wallet to a debit card got. Only LINKED changes where the money is. */
public enum CardLinkStatus {
  /** Card Service has not answered yet. */
  PENDING,
  /** The card exists and is active: the wallet pays and receives through its main account. */
  LINKED,
  /** Card Service did not accept the card. */
  REJECTED
}
