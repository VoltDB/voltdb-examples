/* This file is part of VoltDB.
 * Copyright (C) 2008-2024 Volt Active Data Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE
 * USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package metrocard;

import org.voltdb.client.Client;
import org.voltdbtest.testcontainer.VoltDBCluster;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Benchmark runner that starts VoltDB in Docker, loads DDL,
 * initializes station data, and runs the MetroBenchmark client locally.
 */
public class MetrocardBenchmarkRunner {

    private static final String DEFAULT_DOCKER_IMAGE = "voltdb/voltdb-enterprise:14.3.3";

    // Sample station data for benchmark (IDs start at 0 to match benchmark's rand.nextInt behavior)
    private static final String[][] STATIONS = {
        {"0", "Grand Central", "275", "200"},
        {"1", "Times Square", "275", "180"},
        {"2", "Penn Station", "275", "170"},
        {"3", "Union Square", "275", "120"},
        {"4", "Wall Street", "275", "90"},
        {"5", "Brooklyn Bridge", "275", "85"},
        {"6", "Columbus Circle", "275", "80"},
        {"7", "Fulton Street", "275", "75"},
        {"8", "34th Street", "275", "100"},
        {"9", "14th Street", "275", "95"}
    };

    public static void main(String[] args) throws Exception {
        String licensePath = getLicensePath();
        String dockerImage = getDockerImage();
        // Find the module directory (handles running from root or module directory)
        String moduleDir = findModuleDir("metrocard");

        // Parse duration and set up timeout: max(2 * duration, 10 minutes)
        String duration = getArg(args, "--duration", "60");
        int durationSecs = Integer.parseInt(duration);
        int timeoutSecs = Math.max(durationSecs * 2, 600);

        // Set up a watchdog timer to fail if benchmark hangs
        Timer watchdog = new Timer("BenchmarkWatchdog", true);
        watchdog.schedule(new TimerTask() {
            @Override
            public void run() {
                System.err.println("ERROR: Benchmark timed out after " + timeoutSecs + " seconds!");
                System.err.println("Expected duration was " + durationSecs + " seconds.");
                System.exit(1);
            }
        }, timeoutSecs * 1000L);

        System.out.println("Benchmark timeout set to " + timeoutSecs + " seconds");

        VoltDBCluster db = new VoltDBCluster(licensePath, dockerImage, null).withCommandLogEnabled(false);

        try {
            System.out.println("Starting VoltDB in Docker...");
            db.start();

            System.out.println("Loading classes and DDL...");
            loadSchemaAndClasses(db, moduleDir + "/ddl.sql", moduleDir + "/target/metrocard-1.0-SNAPSHOT.jar");

            System.out.println("Initializing station data...");
            Client client = db.getClient();
            for (String[] station : STATIONS) {
                client.callProcedure("@AdHoc",
                    "INSERT INTO mc_stations (station_id, name, fare, weight) VALUES (" +
                    station[0] + ", '" + station[1] + "', " + station[2] + ", " + station[3] + ")");
            }

            // Verify stations were loaded
            long stationCount = client.callProcedure("@AdHoc",
                "SELECT COUNT(*) FROM mc_stations").getResults()[0].asScalarLong();
            System.out.println("Loaded " + stationCount + " stations (expected " + STATIONS.length + ")");

            if (stationCount != STATIONS.length) {
                throw new RuntimeException("Failed to load all stations!");
            }

            // Build benchmark arguments with defaults
            String servers = db.getHostAndPort();

            String[] benchmarkArgs = {
                "--displayinterval=5",
                "--warmup=5",
                "--duration=" + duration,
                "--servers=" + servers,
                "--cardcount=10000"
            };

            System.out.println("Running MetroBenchmark against " + servers + " for " + duration + "s...");
            MetroBenchmark.main(benchmarkArgs);

        } finally {
            watchdog.cancel();
            System.out.println("Shutting down VoltDB...");
            db.shutdown();
        }

        // Force exit - benchmark threads (Timer, client) may not be daemon threads
        System.exit(0);
    }

    private static String findModuleDir(String moduleName) {
        // Check if running from module directory
        if (new File("ddl.sql").exists()) {
            return ".";
        }
        // Check if running from parent directory
        if (new File(moduleName + "/ddl.sql").exists()) {
            return moduleName;
        }
        throw new RuntimeException("Cannot find " + moduleName + " module directory. " +
            "Run from either the module directory or the parent examples directory.");
    }

    private static String getDockerImage() {
        String image = System.getenv("VOLTDB_IMAGE");
        if (image != null && !image.isEmpty()) {
            return image;
        }
        return DEFAULT_DOCKER_IMAGE;
    }

    private static String getLicensePath() {
        String license = System.getenv("VOLTDB_LICENSE");
        if (license != null && new File(license).exists()) {
            return license;
        }
        String home = System.getProperty("user.home");
        String defaultPath = home + "/license.xml";
        if (new File(defaultPath).exists()) {
            return defaultPath;
        }
        throw new RuntimeException("VoltDB license not found. Set VOLTDB_LICENSE env var or place license.xml in home directory.");
    }

    private static String getArg(String[] args, String key, String defaultValue) {
        for (String arg : args) {
            if (arg.startsWith(key + "=")) {
                return arg.substring(key.length() + 1);
            }
        }
        return defaultValue;
    }

    /**
     * Load procedure classes from JAR, then load DDL filtering out sqlcmd-specific directives.
     */
    private static void loadSchemaAndClasses(VoltDBCluster db, String ddlPath, String jarPath) throws Exception {
        // First load the classes from the JAR
        db.loadClasses(jarPath);

        // Then load the DDL (filtering out sqlcmd-specific directives)
        StringBuilder ddl = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(ddlPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim().toUpperCase();
                // Skip sqlcmd-specific directives and DROP statements (fresh database)
                if (trimmed.startsWith("LOAD CLASSES")) continue;
                if (trimmed.startsWith("DROP ")) continue;
                if (trimmed.startsWith("FILE ")) continue;
                if (trimmed.startsWith("END_OF_")) continue;
                if (trimmed.equals("END_BATCH")) continue;
                ddl.append(line).append("\n");
            }
        }
        db.runDDL(ddl.toString());
    }
}
