-- VoltDB DDL for the beam-threat-detection example.
--
-- The subnet counter (SUBNET_REQUESTS + REQUESTS_PER_SUBNET view) is fed by two
-- real-time paths that both call RecordSubnetRequest:
--   * transaction path — Java microservice, SOURCE_TYPE='TXN'
--   * page-hit path    — Beam streaming pipeline via the VoltDB Beam connector,
--                        SOURCE_TYPE='PAGE'
-- Aggregating across both source types means bot-scan traffic on page hits
-- alone can push a subnet past the rate threshold before the first fraudulent
-- transaction attempt, letting ProcessTransaction reject it inline.
-- Downstream analytics can slice by SOURCE_TYPE when reporting.

-- ============================================
-- Accounts (partitioned on ACCOUNT_ID)
-- ============================================
CREATE TABLE ACCOUNTS (
    ACCOUNT_ID bigint NOT NULL,
    BALANCE decimal DEFAULT 0,
    DAILY_LIMIT decimal DEFAULT 5000,
    NAME varchar(50),
    EMAIL varchar(50),
    PRIMARY KEY (ACCOUNT_ID)
);
PARTITION TABLE ACCOUNTS ON COLUMN ACCOUNT_ID;

-- ============================================
-- Transactions (partitioned on ACCOUNT_ID, co-located with ACCOUNTS)
-- ============================================
CREATE TABLE TRANSACTIONS (
    TXN_ID bigint NOT NULL,
    ACCOUNT_ID bigint NOT NULL,
    TXN_TIME timestamp NOT NULL,
    MERCHANT_ID integer,
    AMOUNT decimal,
    DEVICE_ID varchar(32),
    SOURCE_IP varchar(45),
    ACCEPTED tinyint,
    REASON varchar(100),
    RULE_NAME varchar(30),
    PRIMARY KEY (TXN_ID, ACCOUNT_ID)
);
PARTITION TABLE TRANSACTIONS ON COLUMN ACCOUNT_ID;
CREATE INDEX TXN_TIME_IDX ON TRANSACTIONS (ACCOUNT_ID, TXN_TIME);

-- ============================================
-- Merchants (replicated — small reference table)
-- ============================================
CREATE TABLE MERCHANTS (
    MERCHANT_ID integer NOT NULL,
    NAME varchar(50),
    CATEGORY varchar(20),
    PRIMARY KEY (MERCHANT_ID)
);

-- ============================================
-- Subnet request tracking (partitioned on SUBNET).
-- SOURCE_TYPE is 'TXN' or 'PAGE'. Callers of RecordSubnetRequest must supply it
-- — no server-side default. PAGE_URL is populated only for SOURCE_TYPE='PAGE'.
-- ============================================
CREATE TABLE SUBNET_REQUESTS (
    REQUEST_ID bigint NOT NULL,
    SUBNET varchar(39) NOT NULL,
    SOURCE_IP varchar(45) NOT NULL,
    SOURCE_TYPE varchar(4) NOT NULL,
    PAGE_URL varchar(200),
    REQUEST_TIME timestamp NOT NULL,
    PRIMARY KEY (REQUEST_ID, SUBNET)
);
PARTITION TABLE SUBNET_REQUESTS ON COLUMN SUBNET;

-- ============================================
-- Materialized views for fraud detection rules
-- ============================================

-- TXN_SUMMARY_30SEC: reject if >5 transactions in 30 seconds
CREATE VIEW TXN_SUMMARY_30SEC (
    ACCOUNT_ID,
    WINDOW_30SEC,
    TXN_COUNT,
    TOTAL_SPENT
) AS
    SELECT ACCOUNT_ID,
           TIME_WINDOW(SECOND, 30, TXN_TIME) AS WINDOW_30SEC,
           COUNT(*) AS TXN_COUNT,
           SUM(AMOUNT) AS TOTAL_SPENT
    FROM TRANSACTIONS
    GROUP BY ACCOUNT_ID, TIME_WINDOW(SECOND, 30, TXN_TIME);

-- TXN_SUMMARY_1MIN: reject if >$5,000 spent in 1 minute
CREATE VIEW TXN_SUMMARY_1MIN (
    ACCOUNT_ID,
    WINDOW_1MIN,
    TXN_COUNT,
    TOTAL_SPENT
) AS
    SELECT ACCOUNT_ID,
           TIME_WINDOW(SECOND, 60, TXN_TIME) AS WINDOW_1MIN,
           COUNT(*) AS TXN_COUNT,
           SUM(AMOUNT) AS TOTAL_SPENT
    FROM TRANSACTIONS
    GROUP BY ACCOUNT_ID, TIME_WINDOW(SECOND, 60, TXN_TIME);

-- PAGES_PER_SUBNET + TXNS_PER_SUBNET: two independent 5-second-window counts
-- per CIDR subnet, split by SOURCE_TYPE. The two signals have very different
-- natural baselines — page hits are passive / high-volume, transactions are
-- deliberate / low-volume — so they get independent rate thresholds in
-- ProcessTransaction (SUBNET_PAGE_HIT_RATE, SUBNET_TXN_RATE). ANY rule firing
-- rejects the transaction, same composition as the per-account rules.
CREATE VIEW PAGES_PER_SUBNET (SUBNET, WINDOW_5SEC, PAGE_COUNT) AS
    SELECT SUBNET,
           TIME_WINDOW(SECOND, 5, REQUEST_TIME) AS WINDOW_5SEC,
           COUNT(*) AS PAGE_COUNT
    FROM SUBNET_REQUESTS
    WHERE SOURCE_TYPE = 'PAGE'
    GROUP BY SUBNET, TIME_WINDOW(SECOND, 5, REQUEST_TIME);

CREATE VIEW TXNS_PER_SUBNET (SUBNET, WINDOW_5SEC, TXN_COUNT) AS
    SELECT SUBNET,
           TIME_WINDOW(SECOND, 5, REQUEST_TIME) AS WINDOW_5SEC,
           COUNT(*) AS TXN_COUNT
    FROM SUBNET_REQUESTS
    WHERE SOURCE_TYPE = 'TXN'
    GROUP BY SUBNET, TIME_WINDOW(SECOND, 5, REQUEST_TIME);

-- ============================================
-- DDL-defined procedures (CRUD)
-- ============================================

DROP PROCEDURE UpsertAccount IF EXISTS;
CREATE PROCEDURE UpsertAccount
    PARTITION ON TABLE ACCOUNTS COLUMN ACCOUNT_ID
    AS UPSERT INTO ACCOUNTS (ACCOUNT_ID, BALANCE, DAILY_LIMIT, NAME, EMAIL)
       VALUES (?, ?, ?, ?, ?);

DROP PROCEDURE GetAccount IF EXISTS;
CREATE PROCEDURE GetAccount
    PARTITION ON TABLE ACCOUNTS COLUMN ACCOUNT_ID
    AS SELECT * FROM ACCOUNTS WHERE ACCOUNT_ID = ?;

DROP PROCEDURE UpsertMerchant IF EXISTS;
CREATE PROCEDURE UpsertMerchant
    AS UPSERT INTO MERCHANTS (MERCHANT_ID, NAME, CATEGORY) VALUES (?, ?, ?);

DROP PROCEDURE GetTransactionsByAccount IF EXISTS;
CREATE PROCEDURE GetTransactionsByAccount
    PARTITION ON TABLE TRANSACTIONS COLUMN ACCOUNT_ID
    AS SELECT * FROM TRANSACTIONS WHERE ACCOUNT_ID = ? ORDER BY TXN_TIME DESC;

DROP PROCEDURE SearchBlockedByRule IF EXISTS;
CREATE PROCEDURE SearchBlockedByRule
    AS SELECT * FROM TRANSACTIONS WHERE ACCEPTED = 0 AND RULE_NAME = ? ORDER BY TXN_TIME DESC;

DROP PROCEDURE SearchBlockedByIp IF EXISTS;
CREATE PROCEDURE SearchBlockedByIp
    AS SELECT * FROM TRANSACTIONS WHERE ACCEPTED = 0 AND SOURCE_IP = ? ORDER BY TXN_TIME DESC;

-- ============================================
-- Reporting SP called by the Beam batch pipeline (Path 3).
-- Server-side JOIN denormalizes ACCOUNT_NAME + MERCHANT_NAME + MERCHANT_CATEGORY
-- into the returned row set; the connector just passes the watermark parameter.
-- ============================================
DROP PROCEDURE ReadTxnsSince IF EXISTS;
CREATE PROCEDURE ReadTxnsSince
    AS SELECT t.TXN_ID, t.ACCOUNT_ID, t.TXN_TIME, t.MERCHANT_ID, t.AMOUNT,
              t.DEVICE_ID, t.SOURCE_IP, t.ACCEPTED, t.REASON, t.RULE_NAME,
              a.NAME AS ACCOUNT_NAME,
              m.NAME AS MERCHANT_NAME, m.CATEGORY AS MERCHANT_CATEGORY
       FROM TRANSACTIONS t
       -- LEFT JOIN on both reference tables so validation-rejected transactions
       -- (invalid ACCOUNT_ID / MERCHANT_ID) still surface in the reporting
       -- output — those are threat data worth analysing, just with NULL names.
       LEFT JOIN ACCOUNTS a ON t.ACCOUNT_ID = a.ACCOUNT_ID
       LEFT JOIN MERCHANTS m ON t.MERCHANT_ID = m.MERCHANT_ID
       -- Param is epoch millis (LONG). Explicit TO_TIMESTAMP so the comparison
       -- happens in TIMESTAMP space rather than VoltDB's native microseconds.
       WHERE t.TXN_TIME > TO_TIMESTAMP(MILLISECOND, ?)
       ORDER BY t.TXN_TIME;

-- ============================================
-- Java-backed procedures (business logic)
-- ============================================

DROP PROCEDURE com.voltactivedata.example.threat.procedures.ProcessTransaction IF EXISTS;
DROP PROCEDURE com.voltactivedata.example.threat.procedures.RecordSubnetRequest IF EXISTS;

CREATE PROCEDURE
    PARTITION ON TABLE TRANSACTIONS COLUMN ACCOUNT_ID PARAMETER 0
    FROM CLASS com.voltactivedata.example.threat.procedures.ProcessTransaction;

CREATE PROCEDURE
    PARTITION ON TABLE SUBNET_REQUESTS COLUMN SUBNET PARAMETER 0
    FROM CLASS com.voltactivedata.example.threat.procedures.RecordSubnetRequest;