-- BigQuery + Iceberg destination schemas for the beam-threat-detection reporting
-- pipeline (Path 3). Populated by ReportingPipeline every 2-5 minutes with
-- GeoIP-enriched transaction rows read from VoltDB.
--
-- Substitute your own <project>, <dataset>, <bucket>, <iceberg-catalog> before
-- running.

-- ============================================
-- BigQuery — analytics table (partitioned by TXN_TIME day for cost)
-- ============================================
CREATE TABLE IF NOT EXISTS `<project>.<dataset>.transactions` (
    TXN_ID              INT64      NOT NULL,
    ACCOUNT_ID          INT64      NOT NULL,
    TXN_TIME            TIMESTAMP  NOT NULL,
    MERCHANT_ID         INT64,
    AMOUNT              NUMERIC,
    DEVICE_ID           STRING,
    SOURCE_IP           STRING,
    ACCEPTED            INT64,
    REASON              STRING,
    RULE_NAME           STRING,
    ACCOUNT_NAME        STRING,
    MERCHANT_NAME       STRING,
    MERCHANT_CATEGORY   STRING,
    -- GeoIP enrichment (added by GeoIpEnrichFn in the Beam pipeline)
    GEO_COUNTRY_ISO     STRING,
    GEO_COUNTRY_NAME    STRING,
    GEO_REGION          STRING,
    GEO_CITY            STRING,
    GEO_LATITUDE        FLOAT64,
    GEO_LONGITUDE       FLOAT64,
    GEO_IS_ANONYMOUS    BOOL
)
PARTITION BY DATE(TXN_TIME)
CLUSTER BY ACCOUNT_ID;

-- ============================================
-- Iceberg mirror for transactions — written in parallel by the reporting
-- pipeline (IcebergIO.writeRows), readable from any Iceberg-aware engine
-- (Spark, Trino, Flink, DuckDB) pointed at the GCS location.
--
-- The pipeline writes directly to the Iceberg table catalog defined in its
-- runtime options; no CREATE statement is required against BigQuery here.
-- See the ReportingPipeline / IcebergIO configuration in the Java source for
-- the catalog URI, warehouse path, and table identifier.
-- ============================================

-- ============================================
-- Iceberg-formatted raw page-hit table — created by the RUNBOOK (§7a), not
-- by this file. The PubSub-to-BigQuery native subscription writes directly
-- into it. The storage format is Iceberg Parquet files in GCS; BigQuery
-- queries it natively, and so do external Iceberg engines.
--   See: https://cloud.google.com/bigquery/docs/iceberg-tables
-- ============================================