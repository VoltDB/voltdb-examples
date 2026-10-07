# Notebooks

Interactive analyses + chart renders for the `beam-threat-detection` demo.
Query the warehouse tables produced by the reporting Beam pipeline (BigQuery
dataset `voltdb-operator.beam_threat_detection`) and the raw page-hit stream
(fed by the PubSub→BigQuery native subscription on `threat-page-hits`).

## Setup (one-time)

```bash
cd notebooks
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

For any cell that queries BigQuery, run `gcloud auth application-default login`
once in advance so ADC is available.

## Running

Three ways, all equivalent:

- **IntelliJ IDEA** — open the `.ipynb` file. Plotly figures render natively.
- **VS Code** — open the `.ipynb` file; select the `.venv/bin/python` kernel.
- **Jupyter** — `.venv/bin/jupyter notebook` from this directory.

Headless execution for CI / smoke-testing:

```bash
.venv/bin/jupyter nbconvert --to notebook --execute --output <name>.executed.ipynb <name>.ipynb
```

## Notebook catalog

| Notebook | Story |
|---|---|
| `chart1_subnet_rate_timeline.ipynb` | A page-hit burst on one `/24` pushes the subnet's page-hit counter past the SUBNET_PAGE_HIT_RATE threshold → the next transaction attempt from that subnet is rejected atomically by `ProcessTransaction`. |
| (planned) `chart2_per_account.ipynb` | Per-account velocity / spend rule firing — proves VoltDB's per-account materialized views detect fraud without any subnet-level signal. |
| (planned) `chart3_world_map.ipynb` | Geographic view of blocked transactions, with marker encoding showing which rule family fired (subnet-rate rules vs per-account rules vs validation). |

Each notebook starts with **inline fake data** so you can iterate on the chart
design without any BigQuery dependency. The "swap to BQ" variant is in a
commented block inside each query cell — uncomment when you have a BigQuery
dataset to point at.
