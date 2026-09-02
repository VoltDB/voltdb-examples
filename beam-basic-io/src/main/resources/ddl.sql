-- Minimal DDL for the VoltDB Beam connector basic-io example.
-- Just enough surface to exercise every VoltDbIO operation:
--   * write via UpsertAccount
--   * ad-hoc SQL read via SELECT
--   * stored-procedure read via GetAllAccounts
--   * partitioned parallel read via ScanAccountsPartition

CREATE TABLE ACCOUNTS (
    ACCOUNT_ID   INTEGER      NOT NULL,
    NAME         VARCHAR(64),
    ENABLED      TINYINT      DEFAULT 1,
    BALANCE      DECIMAL,
    DAILY_LIMIT  DECIMAL,
    PRIMARY KEY (ACCOUNT_ID)
);
PARTITION TABLE ACCOUNTS ON COLUMN ACCOUNT_ID;

-- Idempotent write target (UPSERT is safe under Beam at-least-once retries).
CREATE PROCEDURE UpsertAccount
    PARTITION ON TABLE ACCOUNTS COLUMN ACCOUNT_ID
    AS UPSERT INTO ACCOUNTS (ACCOUNT_ID, NAME, ENABLED, BALANCE, DAILY_LIMIT)
       VALUES (?, ?, ?, ?, ?);

-- Multi-partition read via a stored procedure.
CREATE PROCEDURE GetAllAccounts
    AS SELECT ACCOUNT_ID, NAME, ENABLED, BALANCE, DAILY_LIMIT
       FROM ACCOUNTS ORDER BY ACCOUNT_ID;

-- Single-partition scan procedure for partition-parallel reads.
-- PARAMETER 0 is the routing key only; it must not filter rows, so the WHERE
-- clause references the parameter without narrowing the result set.
CREATE PROCEDURE ScanAccountsPartition
    PARTITION ON TABLE ACCOUNTS COLUMN ACCOUNT_ID PARAMETER 0
    AS SELECT ACCOUNT_ID, NAME, ENABLED, BALANCE, DAILY_LIMIT
       FROM ACCOUNTS
       WHERE CAST(? AS INTEGER) IS NOT NULL;