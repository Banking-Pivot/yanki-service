package com.banking.yankiservice.support;

import com.banking.yankiservice.domain.model.DocumentType;
import com.banking.yankiservice.domain.model.PaymentLimits;
import com.banking.yankiservice.domain.model.Wallet;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Wallets and values the unit tests start from. */
public final class Fixtures {

  public static final Instant START = Instant.parse("2026-11-20T15:00:00Z");
  public static final String ANA = "987654321";
  public static final String BETO = "912345678";
  public static final UUID ANA_ID = UUID.fromString("00000000-0000-4000-8000-00000000000a");
  public static final UUID BETO_ID = UUID.fromString("00000000-0000-4000-8000-00000000000b");
  public static final UUID ANA_CARD = UUID.fromString("00000000-0000-4000-8000-0000000000ca");
  public static final UUID BETO_CARD = UUID.fromString("00000000-0000-4000-8000-0000000000cb");
  public static final UUID ANA_LINK = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
  public static final UUID BETO_LINK = UUID.fromString("00000000-0000-4000-8000-0000000000bb");

  /** The limits of the settings: 500 per payment, 2000 per day of Lima. */
  public static final PaymentLimits LIMITS =
      new PaymentLimits(
          new BigDecimal("500.00"), new BigDecimal("2000.00"), ZoneId.of("America/Lima"));

  private Fixtures() {}

  /** A clock stopped a number of seconds after {@link #START}. */
  public static Clock clockAt(long secondsAfterStart) {
    return Clock.fixed(START.plusSeconds(secondsAfterStart), ZoneOffset.UTC);
  }

  /** IDs that can be told apart: the n-th one ends in n. */
  public static Supplier<UUID> ids() {
    AtomicInteger next = new AtomicInteger();
    return () -> new UUID(0x1111L, next.incrementAndGet());
  }

  /** The wallet of Ana, with the balance given and no card. */
  public static Wallet ana(String balance) {
    return funded(
        Wallet.register(
            ANA_ID, DocumentType.DNI, "45678912", ANA, "356938035643809", "ana@example.com", START),
        balance);
  }

  /** The wallet of Beto, with the balance given and no card. */
  public static Wallet beto(String balance) {
    return funded(
        Wallet.register(
            BETO_ID,
            DocumentType.CEX,
            "X12345678",
            BETO,
            "490154203237518",
            "beto@example.com",
            START),
        balance);
  }

  /** The wallet waiting for Card Service to answer about the card. */
  public static Wallet linking(Wallet wallet, UUID linkId, UUID cardId) {
    return wallet.requestCardLink(linkId, cardId, "corr-link", START);
  }

  /** The wallet linked to the card. */
  public static Wallet linked(Wallet wallet, UUID linkId, UUID cardId) {
    return linking(wallet, linkId, cardId)
        .cardResolved(linkId, wallet.getDocumentType().name(), wallet.getDocumentNumber(), START)
        .orElseThrow();
  }

  public static BigDecimal money(String amount) {
    return new BigDecimal(amount);
  }

  private static Wallet funded(Wallet wallet, String balance) {
    BigDecimal amount = new BigDecimal(balance);
    return amount.signum() == 0 ? wallet : wallet.topUp(amount, START);
  }
}
