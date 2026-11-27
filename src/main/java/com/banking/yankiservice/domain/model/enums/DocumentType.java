package com.banking.yankiservice.domain.model.enums;

import com.banking.yankiservice.domain.exception.RuleViolation;
import com.banking.yankiservice.domain.exception.YankiRuleViolationException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Identity documents a wallet can be opened with, each with the shape of its number. */
public enum DocumentType {
  /** Peruvian national identity document: 8 digits. */
  DNI("\\d{8}", "A DNI has 8 digits."),
  /** Foreign resident card: 9 to 12 letters or digits. */
  CEX("[A-Z0-9]{9,12}", "A foreign resident card has 9 to 12 letters or digits."),
  /** Passport: 6 to 12 letters or digits. */
  PASSPORT("[A-Z0-9]{6,12}", "A passport has 6 to 12 letters or digits.");

  private final Pattern shape;
  private final String rule;

  DocumentType(String shape, String rule) {
    this.shape = Pattern.compile(shape);
    this.rule = rule;
  }

  /**
   * The number as it is stored: without surrounding spaces and with letters in upper case.
   *
   * @throws YankiRuleViolationException when the number does not have the shape of this document
   */
  public String normalize(String number) {
    String normalized = number == null ? "" : number.strip().toUpperCase(Locale.ROOT);
    if (!shape.matcher(normalized).matches()) {
      throw new YankiRuleViolationException(RuleViolation.INVALID_DOCUMENT_NUMBER, rule);
    }
    return normalized;
  }
}
