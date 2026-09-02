package org.voltdb.beam.examples.basicio;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.voltdbtest.testcontainer.VoltDBCluster;

import static org.junit.Assume.assumeTrue;

/**
 * Runs the full {@link BasicIoExample} against a VoltDB Testcontainer.
 * Requires Docker and a VoltDB Enterprise license; the license path comes from
 * the {@code voltdb.license.path} system property.
 */
public class BasicIoExampleIT {

    private static final Logger LOG = LoggerFactory.getLogger(BasicIoExampleIT.class);

    private static final String VOLTDB_IMAGE = "voltdb/voltdb-enterprise:15.3.0";

    private static VoltDBCluster voltdbCluster;

    @BeforeClass
    public static void startCluster() throws Exception {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "--version");
            Process process = pb.start();
            int exitCode = process.waitFor();
            assumeTrue("Docker is not available; skipping IT", exitCode == 0);
        } catch (Exception e) {
            assumeTrue("Docker is not available; skipping IT: " + e.getMessage(), false);
        }

        String licensePath = System.getProperty("voltdb.license.path");
        assumeTrue(
                "voltdb.license.path system property not set; skipping IT. "
                        + "Pass -Dvoltdb.license.path=/path/to/license.xml on the mvn command line.",
                licensePath != null && !licensePath.isEmpty());

        LOG.info("Starting VoltDB test cluster (image={}, license={})", VOLTDB_IMAGE, licensePath);

        // ddl.sql lives in src/main/resources; Maven puts main resources on the test
        // classpath, so it's findable by name. withInitialSchema resolves it via
        // testcontainers' MountableFile.forClasspathResource(...) which expects a
        // classpath-relative name, not an absolute filesystem path.
        voltdbCluster = new VoltDBCluster(licensePath, VOLTDB_IMAGE);
        voltdbCluster.withInitialSchema("ddl.sql");
        voltdbCluster.start();

        LOG.info("VoltDB cluster started at {}", voltdbCluster.getHostAndPort());
    }

    @AfterClass
    public static void stopCluster() {
        if (voltdbCluster != null) {
            LOG.info("Stopping VoltDB cluster...");
            voltdbCluster.shutdown();
        }
    }

    @Test
    public void allStepsSucceed() {
        BasicIoExample.main(new String[] {
                "--voltdbHosts=" + voltdbCluster.getHostAndPort(),
                "--seedCount=25",
        });
    }
}
