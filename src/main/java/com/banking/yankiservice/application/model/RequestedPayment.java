package com.banking.yankiservice.application.model;

import com.banking.yankiservice.domain.model.Payment;

/**
 * The answer to a payment request.
 *
 * @param replayed true when the request repeated a payment that was already recorded
 */
public record RequestedPayment(Payment payment, boolean replayed) {}
