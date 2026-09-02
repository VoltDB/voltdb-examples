# basic-io — example pipelines for the VoltDB Beam connector

Runs on the Beam **DirectRunner** locally (via Testcontainers) and on
**Dataflow** against a VoltDB cluster on GKE.

Four Beam pipelines, one per `VoltDbIO` operation:

| Class | Connector operation |
|---|---|
| `LoadAccounts` | `VoltDbIO.write()` → `UpsertAccount` stored procedure |
| `ListAllAccountsViaSql` | `VoltDbIO.read().withQuery(...)` — ad-hoc SQL |
| `ListAllAccountsViaProcedure` | `VoltDbIO.read().withProcedure(...)` — multi-partition SP |
| `ListAllAccountsInParallel` | `VoltDbIO.read().withPartitionedProcedure(...)` — one reader per partition |

Steps 2–4 assert via `PAssert` that the row count matches `--seedCount`. The
`main` method fails with a non-zero exit code if any pipeline finishes in a
non-`DONE` state.

Minimal DDL (`ACCOUNTS` + 3 procedures) lives in
[`src/main/resources/ddl.sql`](src/main/resources/ddl.sql).

## Common prerequisites

- JDK 8+, Maven 3.6+
- Connector installed to your local Maven repo — from the parent repo root:

  ```bash
  # Builds and installs org.voltdb:voltdb-beam-io:1.0.0-SNAPSHOT to ~/.m2
  mvn install -DskipTests
  ```

## Pipeline options

Standard Beam options (`--runner`, `--project`, `--region`, …) plus:

| Flag | Default | Meaning |
|---|---|---|
| `--voltdbHosts` | `localhost:21212` | Comma-separated VoltDB hosts |
| `--voltdbUser` | *(empty)* | VoltDB username (empty for no-auth) |
| `--voltdbPassword` | *(empty)* | VoltDB password (empty for no-auth) |
| `--seedCount` | `100` | Number of `ACCOUNTS` rows to seed |
| `--connectionTimeoutMs` | `60000` | Initial TCP+TLS handshake timeout. Bump for cold Dataflow workers where the first connection can exceed the `voltdbclient` default |
| `--sslEnabled` | `false` | Enable TLS for the client connection |
| `--sslHostnameCheck` | `false` | Verify server hostname against the cert (only used when `--sslEnabled=true`) |
| `--sslPropertyFile` | *(empty)* | SSL props file (trustStore/trustStorePassword/keyStore/keyStorePassword). Takes precedence over the direct flags below |
| `--sslTrustStore` / `--sslTrustStorePassword` | *(empty)* | Path + password for the client trust store |
| `--sslKeyStore` / `--sslKeyStorePassword` | *(empty)* | Path + password for the client key store (only for mTLS) |
| `--secretManagerPasswordSecret` | *(empty)* | Secret Manager resource name (`projects/…/secrets/…/versions/…`) for the VoltDB password. Fetched on the worker at connect time; plaintext never enters the pipeline graph. Overrides `--voltdbPassword` |
| `--secretManagerTrustStoreBytesSecret` | *(empty)* | Secret Manager resource for the trust store JKS bytes payload. Materialized to a temp file on the worker. Overrides `--sslTrustStore` |
| `--secretManagerTrustStorePasswordSecret` | *(empty)* | Secret Manager resource for the trust store password. Overrides `--sslTrustStorePassword` |

---

## Section A — Run locally with Testcontainers

Extra prerequisites:
- Docker running
- VoltDB Enterprise license file

Runs `BasicIoExampleIT.allStepsSucceed` — the single IT class in this project.
Maven's `verify` phase triggers Failsafe, which starts a
`voltdb/voltdb-enterprise:15.3.0` Testcontainer (loads `ddl.sql` on startup),
calls `BasicIoExample.main`, and shuts the container down. `main` runs the 4
pipelines in sequence on the DirectRunner (no `--runner` flag).

Run from the `voltdb-examples/beam-basic-io/` directory:

```bash
# `verify` → failsafe → BasicIoExampleIT.allStepsSucceed → BasicIoExample.main → 4 DirectRunner pipelines (~10s)
mvn verify -Dvoltdb.license.path=/path/to/license.xml
```

### Verify

Expect these lines at the tail of the output:

```
>>> Load reference data (write)                                          — OK
>>> List all accounts via SQL (ad-hoc read)                              — OK
>>> List all accounts via stored procedure (multi-partition read)        — OK
>>> List all accounts in parallel by partition (partition-parallel read) — OK
Basic-io example completed successfully.
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### Logs

Failsafe reports live in `target/failsafe-reports/`. On failure,
`BasicIoExampleIT.txt` in that directory contains the full stack trace.

---

## Section B — Run on GCP (VoltDB on GKE + Dataflow)

Extra prerequisites:
- A GCP project with billing enabled
- Application Default Credentials on the dev machine
  (`gcloud auth application-default login`)
- A GCS bucket for Dataflow staging
- A `dockerio-registry` `kubernetes.io/dockerconfigjson` Secret in some
  reference namespace, for pulling private `voltdb/*` images

Placeholders used below: `<project>`, `<region>`, `<zone>`, `<cluster>`,
`<bucket>`, `<voltdb-operator-path>`, `<license-path>`, `<src-ns>` (namespace
holding the pull secret).

### Dataflow deployment patterns

This section walks through the simplest path — plain `mvn exec:java` against
a no-security VoltDB cluster. Two other deployment patterns are covered in
the connector's Dataflow deployment guide:

- **Custom SDK worker image** — bake certs into a Beam SDK worker image and
  run via `mvn exec:java --sdkContainerImage=…`. Simplest way to add SSL.
- **Flex Template + Secret Manager** — pipeline packaged as a Dataflow
  Flex Template; passwords and cert JKS bytes come from Secret Manager at
  worker time. Nothing sensitive lives on the worker image or in the
  pipeline graph. Uses the single-pipeline entry point
  `WriteAccountsMain` (Flex Templates only allow one pipeline graph per
  template).

Full walk-through with cert / Secret Manager setup:
[voltdb-apache-beam/docs/dataflow-ssl-deployment.md](https://github.com/VoltDB/voltdb-apache-beam/blob/main/docs/dataflow-ssl-deployment.md).

### 1. One-time project setup

```bash
# Enable the Dataflow API (5-min propagation)
gcloud services enable dataflow.googleapis.com --project=<project>

# Turn off soft-delete on the staging bucket so Dataflow's temp-file churn
# does not accumulate billable soft-deleted objects
gcloud storage buckets update gs://<bucket> --clear-soft-delete
```

Verify:
```bash
# Should print SERVICE_STATE: ENABLED
gcloud services list --enabled --filter='name:dataflow.googleapis.com' \
    --project=<project>

# Should print an empty value (soft-delete off) — retentionDurationSeconds=0
gcloud storage buckets describe gs://<bucket> --project=<project> \
    --format='value(soft_delete_policy)'
```

### 2. Deploy VoltDB on GKE

```bash
# Fetch kubectl credentials for the target GKE cluster
gcloud container clusters get-credentials <cluster> \
    --zone <zone> --project <project>

# Set namespace variables for the rest of the section
export NS=marina-test-beam-connector
export SRC_NS=<src-ns>

# Create the namespace
kubectl create namespace $NS

# Copy the Docker Hub pull secret into the new namespace
kubectl get secret dockerio-registry -n $SRC_NS -o yaml \
    | sed "s/namespace: $SRC_NS/namespace: $NS/" \
    | kubectl apply -f -

# Attach the pull secret to the default ServiceAccount
kubectl patch sa default -n $NS \
    -p '{"imagePullSecrets":[{"name":"dockerio-registry"}]}'

# Helm install a single-node, no-security VoltDB cluster (image 15.3.0)
helm install basicio <voltdb-operator-path>/charts/voltdb-operator \
    -n $NS --skip-crds --wait --timeout 10m \
    --set-file cluster.config.licenseXMLFile=<license-path> \
    --set global.voltdbVersion=15.3.0 \
    --set cluster.clusterSpec.replicas=1 \
    --set cluster.clusterSpec.image.repository=voltdb/voltdb-enterprise \
    --set cluster.clusterSpec.image.tag=15.3.0 \
    --set cluster.config.deployment.cluster.kfactor=0

# Load the DDL into the running cluster (from the beam-basic-io/ project dir)
cat src/main/resources/ddl.sql \
    | kubectl exec -i -n $NS basicio-voltdb-cluster-0 -- \
        sqlcmd --servers=localhost

# Create an internal LoadBalancer so Dataflow workers can reach VoltDB
kubectl apply -f - <<EOF
apiVersion: v1
kind: Service
metadata:
  name: basicio-voltdb-ilb
  namespace: $NS
  annotations:
    networking.gke.io/load-balancer-type: "Internal"
    networking.gke.io/internal-load-balancer-allow-global-access: "true"
spec:
  type: LoadBalancer
  selector:
    voltdb-cluster-name: basicio-voltdb-cluster
  ports:
    - name: client
      port: 21212
      targetPort: 21212
      protocol: TCP
EOF
```

Verify:
```bash
# Should print "Running", "1/1"
kubectl -n $NS get pod basicio-voltdb-cluster-0

# Wait until the ILB is provisioned (typically 30–60s), then print the IP.
# If the loop stays stuck, run `kubectl -n $NS describe svc basicio-voltdb-ilb` and read the Events section.
until IP=$(kubectl -n $NS get svc basicio-voltdb-ilb \
    -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null) && [ -n "$IP" ]; do
    echo "waiting for ILB IP..."; sleep 10
done
echo "ILB IP: $IP"

# Should list ACCOUNTS in the first result set and our 3 procedures
# (UpsertAccount, GetAllAccounts, ScanAccountsPartition) among the built-ins in the second
kubectl exec -n $NS basicio-voltdb-cluster-0 -- \
    sqlcmd --servers=localhost --query='exec @SystemCatalog TABLES; exec @SystemCatalog PROCEDURES'
```

### 3. Run the example on Dataflow

```bash
# Fetch the ILB IP dynamically and submit all 4 pipelines
VOLTDB_IP=$(kubectl -n $NS get svc basicio-voltdb-ilb \
    -o jsonpath='{.status.loadBalancer.ingress[0].ip}')

mvn compile exec:java -Pdataflow-runner -B \
    -Dexec.mainClass=org.voltdb.beam.examples.basicio.BasicIoExample \
    -Dexec.args="\
--runner=DataflowRunner \
--project=<project> \
--region=<region> \
--tempLocation=gs://<bucket>/beam-connector-test/temp \
--stagingLocation=gs://<bucket>/beam-connector-test/staging \
--subnetwork=regions/<region>/subnetworks/default \
--maxNumWorkers=1 \
--numWorkers=1 \
--workerMachineType=n1-standard-2 \
--diskSizeGb=25 \
--voltdbHosts=$VOLTDB_IP:21212 \
--seedCount=10"
```

The first run uploads ~250MB of jars to `gs://<bucket>/beam-connector-test/staging/`;
subsequent runs skip already-staged jars.

### Verify

Four sequential Dataflow jobs run, one per pipeline. All four should reach
state `Done`:

```bash
# Lists the 4 most recent jobs; expect STATE=Done for all four
gcloud dataflow jobs list --project=<project> --region=<region> --limit=4 \
    --format='table(name,id,state,createTime)'
```

Or open the console: `https://console.cloud.google.com/dataflow/jobs?project=<project>`.

At the tail of the local `mvn` output, expect:

```
<<< List all accounts in parallel by partition (partition-parallel read) — OK
Basic-io example completed successfully.
BUILD SUCCESS
```

### Logs

Every Dataflow job produces three log streams; which you want depends on what
you're debugging.

**1. Job log — orchestration.** Dataflow's own view (job started, autoscaling,
DONE/FAILED). Not what your code printed. Best for "why did the job take 3 min
to start" or "did it autoscale".

- Console: job page → "Logs" panel at the bottom → tab **"Job log"**.
- CLI:
  ```bash
  gcloud logging read '
      resource.type="dataflow_step"
      resource.labels.job_id="<job-id>"
  ' --project=<project> --limit=50 \
      --format='value(timestamp,severity,jsonPayload.message)'
  ```

**2. Worker logs — what your code emitted.** Everything printed by workers:
Beam SLF4J, `voltdb-beam-io`, `voltdbclient`, your own `LOG.info(...)`, and
stack traces from any DoFn exception. Best for connector errors, stack traces,
VoltDB connection failures, PAssert failures.

- Console: same Logs panel → tab **"Worker logs"**. Click **"Logs Explorer"**
  for the full-page filterable view.
- CLI:
  ```bash
  gcloud logging read '
      resource.type="dataflow_job"
      resource.labels.job_id="<job-id>"
      logName="projects/<project>/logs/dataflow.googleapis.com%2Fworker"
  ' --project=<project> --limit=50 \
      --format='value(timestamp,severity,jsonPayload.message)'
  ```

**3. Worker startup logs — VM boot.** Image pull, harness startup, network
setup. Same panel; log names include `worker-startup`. Rare — only needed for
"worker never became healthy".

**Common patterns:**

```bash
# Errors only
gcloud logging read '
    resource.type="dataflow_step"
    resource.labels.job_id="<job-id>"
    severity>=ERROR
' --project=<project> --limit=20 \
    --format='value(timestamp,jsonPayload.message)'

# Grep for a specific string (e.g. an exception name)
gcloud logging read '
    resource.type="dataflow_step"
    resource.labels.job_id="<job-id>"
    jsonPayload.message=~"IllegalArgumentException"
' --project=<project> --limit=20
```

**Other logs:**

```bash
# High-level job metadata (times, state, options passed at submit)
gcloud dataflow jobs describe <job-id> --project=<project> --region=<region>

# VoltDB server log (SP compilation errors, client connection acceptance, etc.)
kubectl -n $NS logs basicio-voltdb-cluster-0
```

Local orchestrator log is just the stdout of the `mvn exec:java` command.

**Gotchas:**

- `resource.type` differs by log stream — worker logs use `dataflow_job`,
  job/step logs use `dataflow_step`. Getting this wrong returns empty.
- Cloud Logging default retention is 30 days.
- SLF4J levels map directly to Cloud Logging severities: `LOG.info(...)` →
  `INFO`, `LOG.error(...)` → `ERROR`.

---

## Cleanup

```bash
# Between iterations — clear the ACCOUNTS table
kubectl -n $NS exec basicio-voltdb-cluster-0 -- \
    sqlcmd --servers=localhost --query='DELETE FROM ACCOUNTS'

# Full teardown — delete the VoltDBCluster CR FIRST so the operator can clear
# voltdb.com/finalizer.pvc while it's still running, THEN delete the namespace.
# `kubectl delete namespace` alone races: the operator pod gets terminated
# before it clears the finalizer, and the namespace hangs in Terminating.
kubectl -n $NS delete voltdbcluster --all --wait=true
kubectl delete namespace $NS
```

**Reuse-friendly variant** — to keep the namespace (remove only the helm
release, so you can rerun `helm install` later without redoing the pull-secret
+ SA patch):

```bash
kubectl -n $NS delete voltdbcluster --all --wait=true
helm uninstall basicio -n $NS
kubectl -n $NS delete svc basicio-voltdb-ilb
```

**Recovery** — if the namespace gets stuck in `Terminating` (happens when
`helm uninstall` runs before the CR's finalizer is cleared), strip the
finalizer manually:

```bash
kubectl -n $NS patch voltdbcluster basicio-voltdb-cluster \
    --type=merge -p '{"metadata":{"finalizers":[]}}'
```
