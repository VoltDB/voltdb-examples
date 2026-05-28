/* This file is part of VoltDB.
 * Copyright (C) 2008-2022 Volt Active Data Inc.
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
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package windowing;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.voltdb.CLIConfig;
import org.voltdb.VoltTable;
import org.voltdb.client.Client2;
import org.voltdb.client.Client2Config;
import org.voltdb.client.ClientFactory;
import org.voltdb.types.TimestampType;

/**
 * <p>App that does four simultaneous things on a single-table schema:</p>
 *
 * <ol>
 * <li>Insert random, timestamped tuples at a high rate.</li>
 * <li>Continuously delete tuples that are either too old or over a table size limit.</li>
 * <li>Check for changes in the maximum value stored in the table.</li>
 * <li>Periodically compute an average of values over various time windows.</li>
 * </ol>
 *
 * <p>It does this by creating task-focused classes that implement Runnable.
 * Each class has a specific job and is scheduled to run periodically in a
 * threadpool. All inter-task communication is done via the main instance of
 * this class.</p>
 *
 */
public class WindowingApp {

    // handy, rather than typing this out several times
    static final String HORIZONTAL_RULE =
            "----------" + "----------" + "----------" + "----------" +
            "----------" + "----------" + "----------" + "----------" + "\n";

    private final AtomicLong deletesSinceLastChecked = new AtomicLong(0);

    // the value is updated by the UpdatePartitionData class each time it is run
    private final AtomicLong targetRowsPerPartition = new AtomicLong(Long.MAX_VALUE);

    /**
     * Uses included {@link CLIConfig} class to
     * declaratively state command line options with defaults
     * and validation.
     */
    static class WindowingConfig extends CLIConfig {
        @Option(desc = "Interval for performance feedback, in seconds.")
        long displayinterval = 5;

        @Option(desc = "Duration, in seconds.")
        int duration = 120;

        @Option(desc = "Warmup duration, in seconds.")
        int warmup = 5;

        @Option(desc = "Comma separated list of the form server[:port] to connect to.")
        String servers = "localhost";

        @Option(desc = "User name for connection.")
        String user = "";

        @Option(desc = "Password for connection.")
        String password = "";

        @Option(desc = "Global maximum row target. Zero if using history target.")
        long maxrows = 0;

        @Option(desc = "Global maximum history targert. Zero if using row count target.")
        long historyseconds = 30;

        @Option(desc = "Run deletes in the same transaction as inserts.")
        boolean inline = true;

        @Option(desc = "Maximum number of rows to delete in one transaction.")
        long deletechunksize = 100;

        @Option(desc = "Time to pause between deletes when there was nothing to delete at last check.")
        long deleteyieldtime = 100;

        @Option(desc = "Maximum TPS rate for inserts.")
        int ratelimit = 15000;

        @Override
        public void validate() {
            if (duration <= 0) exitWithMessageAndUsage("duration must be > 0");
            if (warmup < 0) exitWithMessageAndUsage("warmup must be >= 0");
            if (displayinterval <= 0) exitWithMessageAndUsage("displayinterval must be > 0");
            if (maxrows < 0) exitWithMessageAndUsage("maxrows must be >= 0");
            if (historyseconds < 0) exitWithMessageAndUsage("historyseconds must be >= 0");
            if (ratelimit <= 0) exitWithMessageAndUsage("ratelimit must be > 0");
            if (deletechunksize <= 0) exitWithMessageAndUsage("deletechunksize must be > 0");
            if (deleteyieldtime < 0) exitWithMessageAndUsage("deleteyieldtime must be >= 0");
            if ((historyseconds > 0) && (maxrows > 0)) {
                exitWithMessageAndUsage("only one of historyseconds or maxrows can be nonzero");
            }
        }
    }

    /////
    // PACKAGE VISIBLE SHARED STATE ACCESS BELOW
    /////

    // Reference to the database connection we will use
    final Client2 client;

    // validated command line configuration
    final WindowingConfig config;

    final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);

    // Made non-final to allow reset after warmup
    long startTS = System.currentTimeMillis();

    final ContinuousDeleter deleter;
    final RandomDataInserter inserter;
    final MaxTracker maxTracker;
    final Reporter reporter;

    long getTargetRowsPerPartition() {
        return targetRowsPerPartition.get();
    }

    TimestampType getTargetDate() {
        long targetTimestampMillis = System.currentTimeMillis() - (config.historyseconds * 1000);
        return new TimestampType(targetTimestampMillis * 1000);
    }

    void updatePartitionCount(long partitionSize) {
        assert(partitionSize > 0);
        targetRowsPerPartition.set(config.maxrows / partitionSize);
    }

    void addToDeletedTuples(long count) {
        deletesSinceLastChecked.addAndGet(count);
    }

    long getDeletesSinceLastChecked() {
        return deletesSinceLastChecked.getAndSet(0);
    }

    void shutdown() {
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        try {
            // block until all outstanding txns return
            client.drain();
            // close down the client connections
            client.close();
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Constructor for benchmark instance.
     * Configures VoltDB client and prints configuration.
     *
     * @param config Parsed & validated CLI options.
     */
    public WindowingApp(WindowingConfig config) {
        this.config = config;

        System.out.print(HORIZONTAL_RULE);
        System.out.println(" Command Line Configuration");
        System.out.println(HORIZONTAL_RULE);

        System.out.println(config.getConfigDumpString());

        System.out.print(HORIZONTAL_RULE);
        System.out.println(" Setup & Initialization");
        System.out.println(HORIZONTAL_RULE);

        // Two separate Client2 instances are used: one for inserts (stats
        // tracked by the inserter) and another for everything else (deletes,
        // tracking, reporting). Each builds its own Client2Config.
        Client2 insertsClient = ClientFactory.createClient(newClientConfig());
        client = ClientFactory.createClient(newClientConfig());

        // connect to one or more servers, loop until success
        try {
            connect(insertsClient, config.servers);
            connect(client, config.servers);
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        deleter = new ContinuousDeleter(this);
        inserter = new RandomDataInserter(this, insertsClient);
        maxTracker = new MaxTracker(this);
        reporter = new Reporter(this);
    }

    /**
     * Build a fresh Client2Config applying this app's CLI options.
     */
    private Client2Config newClientConfig() {
        Client2Config cc = new Client2Config()
                .connectFailureHandler(this::connectFailed)
                .connectionUpHandler(this::connectionUp)
                .connectionDownHandler(this::connectionDown)
                .errorLogHandler(this::logError);
        if (!config.user.isEmpty()) {
            cc.username(config.user).password(config.password);
        }
        if (config.ratelimit != Integer.MAX_VALUE) {
            cc.transactionRateLimit(config.ratelimit);
        }
        return cc;
    }

    // --- Client2 connection event handlers ---

    void connectFailed(String host, int port) {
        System.err.printf("Connect failed: %s:%d%n", host, port);
    }

    void connectionUp(String host, int port) {
        System.out.printf("Connection up: %s:%d%n", host, port);
    }

    void connectionDown(String host, int port) {
        // if the benchmark is still active, note if a server disconnects
        final long benchmarkEndTime = startTS + (1000L * config.duration);
        if (System.currentTimeMillis() < benchmarkEndTime) {
            System.err.printf("Connection to %s:%d was lost.%n", host, port);
        }
    }

    void logError(String text) {
        System.err.println("Client error: " + text);
    }

    /**
     * Connect to the cluster. Client2 is topology-aware — a single connectAsync
     * discovers the rest of the cluster.
     */
    static void connect(final Client2 client, String servers) throws Exception {
        System.out.println("Connecting to the database cluster...");
        client.connectAsync(servers, 120, 10, TimeUnit.SECONDS).get();
    }

    /**
     * Core benchmark code.
     * Connect. Initialize. Run the loop. Cleanup. Print Results.
     *
     * @throws Exception if anything unexpected happens.
     */
    public void run() {

        System.out.println(HORIZONTAL_RULE);
        System.out.println(" Getting initial database partition count");
        long partitionCount = 0;
        try {
            VoltTable results[] = client.callProcedureSync("@GetPartitionKeys", "integer").getResults();
            partitionCount = results[0].getRowCount();
            updatePartitionCount(partitionCount);
        } catch (Exception e) {
            System.out.print(HORIZONTAL_RULE);
            System.out.println("Could not get partition information. Processing terminated. Error:" + e.getMessage());
            e.printStackTrace();
            shutdown();
            System.out.print(HORIZONTAL_RULE);
            return;
        }
        System.out.println(" Initial database partition count: " + partitionCount);

        // Warmup phase
        if (config.warmup > 0) {
            System.out.print(HORIZONTAL_RULE);
            System.out.println(" Warming up for " + config.warmup + " seconds...");
            System.out.println(HORIZONTAL_RULE);

            // Run warmup - just let inserts run for warmup period
            final long warmupEndTime = System.currentTimeMillis() + (1000L * config.warmup);
            while (warmupEndTime > System.currentTimeMillis()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }
            }

            // Reset stats after warmup
            inserter.periodicStatsContext.fetchAndResetBaseline();

            // Reset start time for actual benchmark
            startTS = System.currentTimeMillis();
            System.out.println(" Warmup complete. Starting benchmark...");
        }

        System.out.print(HORIZONTAL_RULE);
        System.out.println(" Starting Processing");
        System.out.println(HORIZONTAL_RULE);

        // Print periodic stats/analysis to the console
        scheduler.scheduleWithFixedDelay(reporter,
                                               config.displayinterval,
                                               config.displayinterval,
                                               TimeUnit.SECONDS);

        if (!config.inline) {
            // Delete data as often as need be
            //  -- This will resubmit itself at varying rates according to insert load
            scheduler.execute(deleter);
        }

        // Start tracking changes to the maximum value after a 5 second delay to
        // let things settle a bit. Then check up to 100 times per second.
        scheduler.scheduleWithFixedDelay(maxTracker,
                                               5000,
                                               10,
                                               TimeUnit.MILLISECONDS);

        // Run the benchmark loop for the requested duration
        // The throughput may be throttled depending on client configuration
        inserter.run();

        System.out.print(HORIZONTAL_RULE);
        System.out.println(" Processing Complete");
        System.out.println(HORIZONTAL_RULE);

        shutdown();
    }

    /**
     * Main routine creates an instance of this app and kicks off the run method.
     *
     * @param args Command line arguments.
     * @throws Exception if anything goes wrong.
     * @see {@link WindowingConfig}
     */
    public static void main(String[] args) throws Exception {
        // create a configuration from the arguments
        WindowingConfig config = new WindowingConfig();
        config.parse(WindowingApp.class.getName(), args);

        WindowingApp app = new WindowingApp(config);
        app.run();
    }
}
