package com.banking.yankiservice.application.command;

import java.util.UUID;

/** Replaces the contact data of a wallet: the IMEI of the phone and the email. */
public record UpdateWalletCommand(UUID walletId, String imei, String email) {}
