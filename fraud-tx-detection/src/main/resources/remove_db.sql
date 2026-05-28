-- VoltDB Remove Schema — drops all objects in dependency order

-- Step 1: Drop procedures first (they reference tables)
-- Java class procedures (fully qualified name)
DROP PROCEDURE com.example.voltdb.procedures.ProcessTransaction IF EXISTS;
-- DDL-defined procedures (short name)
DROP PROCEDURE UpsertAccount IF EXISTS;
DROP PROCEDURE UpsertMerchant IF EXISTS;
DROP PROCEDURE GetAccount IF EXISTS;

-- Step 2: Drop views (they depend on TRANSACTIONS)
DROP VIEW TXN_SUMMARY_30SEC IF EXISTS;
DROP VIEW TXN_SUMMARY_1MIN IF EXISTS;
DROP VIEW TXN_SUMMARY_5MIN IF EXISTS;

-- Step 3: Drop co-located table
DROP TABLE TRANSACTIONS IF EXISTS;

-- Step 4: Drop replicated table
DROP TABLE MERCHANTS IF EXISTS;

-- Step 5: Drop primary table last
DROP TABLE ACCOUNTS IF EXISTS;
