# VoltDB Examples - TODO

## Modules Missing Integration Tests

| Module | Status | Notes |
|--------|--------|-------|
| client2 | Missing | No src/test/java directory |
| contentionmark | Missing | No src/test/java directory, uses default package |
| voltkv | Missing | Only has BenchmarkRunner, no IT tests |

## Modules Missing Benchmarks

| Module | Has Integration Tests | Notes |
|--------|----------------------|-------|
| client2 | No | Needs both IT tests and benchmarks |
| contentionmark | No | Main class uses default package - cannot be called from packaged code |
| fraud-detection | Yes | Uses Kafka, may need different benchmark approach |

## Modules Complete (Have Both IT Tests and Benchmarks)

| Module | Integration Tests | Benchmarks |
|--------|------------------|------------|
| adperformance | Yes | Yes |
| bank-offers | Yes | Yes |
| callcenter | Yes | Yes |
| geospatial | Yes | Yes |
| json-sessions | Yes | Yes |
| metrocard | Yes | Yes |
| nbbo | Yes | Yes |
| positionkeeper | Yes | Yes |
| simple | Yes | Yes |
| uniquedevices | Yes | Yes |
| voter | Yes | Yes |
| voltkv | No | Yes |
| windowing | Yes | Yes |

## Priority Order for Remaining Work

1. **voltkv** - Add integration tests (already has benchmark)
2. **client2** - Add integration tests and benchmark
3. **contentionmark** - Restructure to use packages, then add IT tests and benchmark
4. **fraud-detection** - Add benchmark (uses Kafka, complex)

## Notes

- All modules are mavenized and included in parent pom.xml
- Benchmark runners use VoltDB Testcontainers to start VoltDB in Docker
- Integration tests also use Testcontainers
- Some modules (fraud-detection) use external systems (Kafka) which may require additional setup for benchmarks
- contentionmark uses default package (no package declaration) which prevents packaged test code from referencing it
