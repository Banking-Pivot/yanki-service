package com.banking.yankiservice.application.command;

import com.banking.yankiservice.domain.model.DocumentType;

/** Opens a wallet for a person identified by a document and a mobile phone. */
public record RegisterWalletCommand(
    DocumentType documentType,
    String documentNumber,
    String phoneNumber,
    String imei,
    String email) {}
