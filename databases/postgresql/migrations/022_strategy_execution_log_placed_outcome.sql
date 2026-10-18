-- The order route answers NEW, not FILLED -- a fill happens later, asynchronously, in the
-- executor. strategy_execution_log was logging "FILLED" the moment an order was accepted, which
-- claimed a fill that had not happened yet. Logging "PLACED" instead needs this value allowed.

ALTER TABLE strategy_execution_log
    DROP CONSTRAINT ck_strategy_execution_log_outcome;

ALTER TABLE strategy_execution_log
    ADD CONSTRAINT ck_strategy_execution_log_outcome
        CHECK (outcome IN ('PLACED', 'REJECTED', 'SKIPPED'));
