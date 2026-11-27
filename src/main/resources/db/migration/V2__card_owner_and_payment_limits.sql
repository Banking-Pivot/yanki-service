-- A debit card has one wallet. Wallets that share a card from before this rule keep one of them:
-- the linked one before one that still waits, then the one that asked first. The request of the
-- others is left rejected, the same way the service refuses it from now on.
UPDATE wallets
SET card_link_status = 'REJECTED',
    card_rejection_code = 'CARD_ALREADY_LINKED',
    card_rejection_message = 'The debit card is linked to another wallet.',
    updated_at = now(),
    version = version + 1
WHERE id IN (
    SELECT ranked.id
    FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY card_id
                   ORDER BY CASE card_link_status WHEN 'LINKED' THEN 0 ELSE 1 END,
                            card_command_sent_at,
                            id) AS position
        FROM wallets
        WHERE card_link_status IN ('PENDING', 'LINKED')) ranked
    WHERE ranked.position > 1);

-- Only one wallet can be linked to a card or wait for it, also when two ask at the same time.
-- A rejected request does not hold the card.
CREATE UNIQUE INDEX uk_wallets_active_card ON wallets (card_id)
    WHERE card_link_status IN ('PENDING', 'LINKED');

-- What a wallet sent in a day: what the daily limit of payments reads.
CREATE INDEX ix_payments_sender_day ON payments (from_wallet_id, created_at);
