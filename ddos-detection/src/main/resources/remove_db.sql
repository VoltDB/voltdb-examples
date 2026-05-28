-- VoltDB Remove Schema -- drops all objects in dependency order

-- Step 1: Drop procedures first
-- Java class procedures (fully qualified name)
DROP PROCEDURE com.example.voltdb.procedures.ProcessRequest IF EXISTS;
DROP PROCEDURE com.example.voltdb.procedures.GetRequestsByIp IF EXISTS;
-- DDL-defined procedures (short name)
DROP PROCEDURE GetBlockedIps IF EXISTS;
DROP PROCEDURE UpsertDomain IF EXISTS;
DROP PROCEDURE SearchBlockedIps IF EXISTS;

-- Step 2: Drop views (depend on REQUESTS)
DROP VIEW REQUESTS_PER_IP_DOMAIN_1SEC IF EXISTS;
DROP VIEW REQUESTS_PER_IP_1SEC IF EXISTS;

-- Step 3: Drop co-located tables
DROP TABLE BLOCKED_IPS IF EXISTS;

-- Step 4: Drop replicated tables
DROP TABLE DOMAINS IF EXISTS;

-- Step 5: Drop primary table last
DROP TABLE REQUESTS IF EXISTS;
