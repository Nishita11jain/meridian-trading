/*
An account transaction referencing a non-existent account must be rejected.
The account_id is generated so that no matching accounts.id row exists.
*/
INSERT INTO account_transactions (
    account_id,
    transaction_type,
    amount
)
VALUES (
    999999999,
    'DEPOSIT',
    100.00
);
