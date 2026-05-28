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

package voltkv;

import org.voltdbtest.testcontainer.VoltDBCluster;

import java.io.File;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Benchmark runner that starts VoltDB in Docker, loads DDL,
 * and runs the AsyncBenchmark client locally against it.
 */
public class VoltKVBenchmarkRunner {

    private static final String DEFAULT_DOCKER_IMAGE = "voltdb/voltdb-enterprise:14.3.3";

    public static void main(String[] args) throws Exception {
        String licensePath = getLicensePath();
        String dockerImage = getDockerImage();
        // Find the module directory (handles running from root or module directory)
        String moduleDir = findModuleDir("voltkv");

        // Parse duration and set up timeout: max(2 * duration, 10 minutes)
        String duration = getArg(args, "--duration", "60");
        int durationSecs = Integer.parseInt(duration);
        int timeoutSecs = Math.max(durationSecs * 2, 600); // 600 = 10 minutes

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

            System.out.println("Loading DDL...");
            db.runDDL(new File(moduleDir + "/ddl.sql"));

            // Build benchmark arguments with defaults
            String servers = db.getHostAndPort();

            String[] benchmarkArgs = {
                "--displayinterval=5",
                "--warmup=5",
                "--duration=" + duration,
                "--servers=" + servers,
                "--poolsize=100000",
                "--preload=true",
                "--getputratio=0.90"
            };

            System.out.println("Running AsyncBenchmark against " + servers + " for " + duration + "s...");
            AsyncBenchmark.main(benchmarkArgs);

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
}
