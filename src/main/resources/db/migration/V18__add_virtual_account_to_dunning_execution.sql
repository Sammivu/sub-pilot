-- V{next}__add_virtual_account_to_dunning_execution.sql
ALTER TABLE dunning_executions
    ADD COLUMN va_bank_name      VARCHAR(100),
    ADD COLUMN va_account_number VARCHAR(20),
    ADD COLUMN va_account_name   VARCHAR(100),
    ADD COLUMN va_account_ref    VARCHAR(64);