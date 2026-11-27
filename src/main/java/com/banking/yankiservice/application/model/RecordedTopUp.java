package com.banking.yankiservice.application.model;

import com.banking.yankiservice.domain.model.WalletMovement;

/**
 * The answer to a top-up request.
 *
 * @param replayed true when the request repeated a top-up that was already recorded
 */
public record RecordedTopUp(WalletMovement movement, boolean replayed) {}
