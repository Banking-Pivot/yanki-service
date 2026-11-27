-- Yanki Service owns the balance of every wallet. Money is NUMERIC(19, 2) in PEN. Instants are
-- TIMESTAMPTZ and the application writes them in UTC. Enum columns are VARCHAR with CHECK
-- constraints.

-- One row per wallet, with the debit card it asked to be linked to. A wallet is found by its
-- mobile number; the number and the identity document have one wallet each.
CREATE TABLE wallets (
    id                      UUID           NOT NULL,
    document_type           VARCHAR(10)    NOT NULL,
    document_number         VARCHAR(12)    NOT NULL,
    phone_number            VARCHAR(9)     NOT NULL,
    imei                    VARCHAR(15)    NOT NULL,
    email                   VARCHAR(254)   NOT NULL,
    balance                 NUMERIC(19, 2) NOT NULL,
    currency                VARCHAR(3)     NOT NULL,
    card_id                 UUID,
    -- ID of the link request: the transactionId of the RESOLVE_CARD command and of its answer.
    card_link_id            UUID,
    card_link_status        VARCHAR(10),
    card_rejection_code     VARCHAR(60),
    card_rejection_message  VARCHAR(300),
    card_correlation_id     VARCHAR(64),
    -- When RESOLVE_CARD was last sent and how many times it was repeated.
    card_command_sent_at    TIMESTAMPTZ,
    card_command_attempts   INTEGER        NOT NULL,
    created_at              TIMESTAMPTZ    NOT NULL,
    updated_at              TIMESTAMPTZ    NOT NULL,
    version                 BIGINT         NOT NULL,
    CONSTRAINT pk_wallets PRIMARY KEY (id),
    CONSTRAINT uk_wallets_phone UNIQUE (phone_number),
    CONSTRAINT uk_wallets_document UNIQUE (document_type, document_number),
    CONSTRAINT uk_wallets_card_link UNIQUE (card_link_id),
    CONSTRAINT ck_wallets_document_type CHECK (document_type IN ('DNI', 'CEX', 'PASSPORT')),
    CONSTRAINT ck_wallets_currency CHECK (currency = 'PEN'),
    CONSTRAINT ck_wallets_balance CHECK (balance >= 0),
    CONSTRAINT ck_wallets_card_link_status CHECK (
        card_link_status IN ('PENDING', 'LINKED', 'REJECTED')),
    -- A wallet has a whole link request or none.
    CONSTRAINT ck_wallets_card_link CHECK (
        (card_id IS NULL AND card_link_id IS NULL AND card_link_status IS NULL
            AND card_correlation_id IS NULL AND card_command_sent_at IS NULL)
        OR (card_id IS NOT NULL AND card_link_id IS NOT NULL AND card_link_status IS NOT NULL
            AND card_correlation_id IS NOT NULL AND card_command_sent_at IS NOT NULL)),
    CONSTRAINT ck_wallets_card_rejection CHECK (
        (card_rejection_code IS NOT NULL)
        = (card_link_status IS NOT NULL AND card_link_status = 'REJECTED')
        AND (card_rejection_code IS NULL) = (card_rejection_message IS NULL)),
    CONSTRAINT ck_wallets_counters CHECK (card_command_attempts >= 0 AND version >= 0)
);

CREATE INDEX ix_wallets_created ON wallets (created_at DESC, id);

-- Wallets whose card has not been answered for, oldest command first: what the periodic
-- re-send reads.
CREATE INDEX ix_wallets_card_pending ON wallets (card_command_sent_at)
    WHERE card_link_status = 'PENDING';

-- One row per payment. The ID is the operationId of the client, so the primary key is also what
-- makes a repeated request find the first one. The cards are the ones the wallets were linked
-- to when the payment was requested; an empty one means that side uses the wallet balance.
CREATE TABLE payments (
    id                   UUID           NOT NULL,
    from_wallet_id       UUID           NOT NULL,
    to_wallet_id         UUID           NOT NULL,
    from_phone_number    VARCHAR(9)     NOT NULL,
    to_phone_number      VARCHAR(9)     NOT NULL,
    amount               NUMERIC(19, 2) NOT NULL,
    currency             VARCHAR(3)     NOT NULL,
    description          VARCHAR(140),
    source_card_id       UUID,
    destination_card_id  UUID,
    status               VARCHAR(10)    NOT NULL,
    fee                  NUMERIC(19, 2),
    rejection_code       VARCHAR(60),
    rejection_message    VARCHAR(300),
    -- Correlation identifier of the request, written in every message of the payment.
    correlation_id       VARCHAR(64)    NOT NULL,
    -- When MOVE_FUNDS was last sent and how many times it was repeated.
    command_sent_at      TIMESTAMPTZ    NOT NULL,
    command_attempts     INTEGER        NOT NULL,
    created_at           TIMESTAMPTZ    NOT NULL,
    updated_at           TIMESTAMPTZ    NOT NULL,
    version              BIGINT         NOT NULL,
    CONSTRAINT pk_payments PRIMARY KEY (id),
    CONSTRAINT fk_payments_from_wallet FOREIGN KEY (from_wallet_id) REFERENCES wallets (id),
    CONSTRAINT fk_payments_to_wallet FOREIGN KEY (to_wallet_id) REFERENCES wallets (id),
    CONSTRAINT ck_payments_wallets CHECK (from_wallet_id <> to_wallet_id),
    CONSTRAINT ck_payments_currency CHECK (currency = 'PEN'),
    CONSTRAINT ck_payments_amount CHECK (amount > 0),
    CONSTRAINT ck_payments_status CHECK (status IN ('PENDING', 'COMPLETED', 'REJECTED')),
    -- Only a payment that needs a bank account waits or can be rejected.
    CONSTRAINT ck_payments_local CHECK (
        status = 'COMPLETED' OR source_card_id IS NOT NULL OR destination_card_id IS NOT NULL),
    CONSTRAINT ck_payments_rejection CHECK (
        (rejection_code IS NOT NULL) = (status = 'REJECTED')
        AND (rejection_code IS NULL) = (rejection_message IS NULL)),
    CONSTRAINT ck_payments_counters CHECK (command_attempts >= 0 AND version >= 0)
);

-- Payments still waiting for Transaction Service, oldest command first: what the periodic
-- re-send reads.
CREATE INDEX ix_payments_pending ON payments (command_sent_at) WHERE status = 'PENDING';

-- What happened to a wallet, one row per movement. The unique key makes each effect of an
-- operation happen once per wallet: one top-up per operationId, and one sent, one received and
-- one refund per payment.
CREATE TABLE wallet_movements (
    id                         UUID           NOT NULL,
    wallet_id                  UUID           NOT NULL,
    movement_type              VARCHAR(20)    NOT NULL,
    funded_by                  VARCHAR(10)    NOT NULL,
    amount                     NUMERIC(19, 2) NOT NULL,
    -- Own balance of the wallet after the movement; one funded by CARD does not change it.
    balance_after              NUMERIC(19, 2) NOT NULL,
    operation_id               UUID           NOT NULL,
    counterparty_phone_number  VARCHAR(9),
    description                VARCHAR(140),
    occurred_at                TIMESTAMPTZ    NOT NULL,
    CONSTRAINT pk_wallet_movements PRIMARY KEY (id),
    CONSTRAINT fk_wallet_movements_wallet FOREIGN KEY (wallet_id) REFERENCES wallets (id),
    CONSTRAINT uk_wallet_movements_operation UNIQUE (wallet_id, operation_id, movement_type),
    CONSTRAINT ck_wallet_movements_type CHECK (
        movement_type IN ('TOP_UP', 'PAYMENT_SENT', 'PAYMENT_RECEIVED', 'REFUND')),
    CONSTRAINT ck_wallet_movements_funded_by CHECK (funded_by IN ('WALLET', 'CARD')),
    CONSTRAINT ck_wallet_movements_amount CHECK (amount > 0),
    CONSTRAINT ck_wallet_movements_balance CHECK (balance_after >= 0)
);

CREATE INDEX ix_wallet_movements_wallet ON wallet_movements (wallet_id, occurred_at DESC, id);
