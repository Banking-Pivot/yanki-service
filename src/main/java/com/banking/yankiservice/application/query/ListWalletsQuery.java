package com.banking.yankiservice.application.query;

/**
 * A page of wallets.
 *
 * @param phoneNumber only the wallet of this mobile number; null for all
 */
public record ListWalletsQuery(PageRequest pageRequest, String phoneNumber) {}
