package com.banking.yankiservice.application.query;

import java.util.UUID;

/** A page of the movements of one wallet. */
public record ListMovementsQuery(UUID walletId, PageRequest pageRequest) {}
