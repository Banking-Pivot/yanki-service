-- A wallet can be closed. It is never deleted: the row and its movements stay, and so do its
-- mobile number and its identity document, which keep their unique keys. Wallets from before
-- this column are ACTIVE.
ALTER TABLE wallets
    ADD COLUMN status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    ADD CONSTRAINT ck_wallets_status CHECK (status IN ('ACTIVE', 'CLOSED'));
