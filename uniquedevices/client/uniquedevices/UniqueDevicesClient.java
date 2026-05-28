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
/*
 * This samples uses the native asynchronous request processing protocol
 * to post requests to the VoltDB server, thus leveraging to the maximum
 * VoltDB's ability to run requests in parallel on multiple database
 * partitions, and multiple servers.
 *
 * While asynchronous processing is (marginally) more convoluted to work
 * with and not adapted to all workloads, it is the preferred interaction
 * model to VoltDB as it allows a single client with a small amount of
 * threads to flood VoltDB with requests, guaranteeing blazing throughput
 * performance.
 *
 * Note that this benchmark focuses on throughput performance and
 * not low latency performance.  This benchmark will likely 'firehose'
 * the database cluster (if the cluster is too slow or has too few CPUs)
 * and as a result, queue a significant amount of requests on the server
 * to maximize throughput measurement. To test VoltDB latency, run the
 * SyncBenchmark client, also found in the voter sample directory.
 */

package uniquedevices;

import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.voltdb.CLIConfig;
import org.voltdb.client.Client2;
import org.voltdb.client.Client2Config;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.ClientStats;
import org.voltdb.client.ClientStatsContext;
import org.voltdb_hll.MurmurHash;

public class UniqueDevicesClient {

    // handy, rather than typing this out several times
    static final String HORIZONTAL_RULE =
            "----------" + "----------" + "----------" + "----------" +
            "----------" + "----------" + "----------" + "----------" + "\n";

    // validated command line configuration
    final UniqueDevicesConfig config;
    // Reference to the database connection we will use
    final Client2 client;
    // Timer for periodic stats printing
    Timer timer;
    // Benchmark start time
    long benchmarkStartTS;
    // Statistics manager objects from the client
    final ClientStatsContext periodicStatsContext;
    final ClientStatsContext fullStatsContext;

    // Client2 backpressure flag — pauses submission loop while set.
    final AtomicBoolean backpressure = new AtomicBoolean(false);

    UniqueIdGenerator generator;

    // voter benchmark state
    AtomicLong totalVotes = new AtomicLong(0);
    AtomicLong acceptedVotes = new AtomicLong(0);
    AtomicLong failedVotes = new AtomicLong(0);

    /**
     * Uses included {@link CLIConfig} class to
     * declaratively state command line options with defaults
     * and validation.
     */
    static class UniqueDevicesConfig extends CLIConfig {
        @Option(desc = "Interval for performance feedback, in seconds.")
        long displayinterval = 5;

        @Option(desc = "Benchmark duration, in seconds.")
        int duration = 20;

        @Option(desc = "Warmup duration, in seconds.")
        int warmup = 5;

        @Option(desc = "Comma separated list of the form server[:port] to connect to.")
        String servers = "localhost";

        @Option(desc = "Number of individual apps to simulate.")
        int appcount = 1;

        @Option(desc = "Maximum TPS rate for benchmark.")
        int ratelimit = Integer.MAX_VALUE;

        @Option(desc = "Filename to write raw summary statistics to.")
        String statsfile = "";

        @Option(desc = "User name for connection.")
        String user = "";

        @Option(desc = "Password for connection.")
        String password = "";

        @Override
        public void validate() {
            if (duration <= 0) exitWithMessageAndUsage("duration must be > 0");
            if (warmup < 0) exitWithMessageAndUsage("warmup must be >= 0");
            if (displayinterval <= 0) exitWithMessageAndUsage("displayinterval must be > 0");
            if (appcount <= 0) exitWithMessageAndUsage("contestants must be > 0");
            if (ratelimit <= 0) exitWithMessageAndUsage("ratelimit must be > 0");
        }
    }

    /**
     * Constructor for benchmark instance.
     * Configures VoltDB client and prints configuration.
     *
     * @param config Parsed & validated CLI options.
     */
    public UniqueDevicesClient(UniqueDevicesConfig config) {
        this.config = config;

        generator = new UniqueIdGenerator(config.appcount);

        Client2Config clientConfig = new Client2Config()
                .clientRequestLimit(20_000)
                .outstandingTransactionLimit(20_000)
                .connectFailureHandler(this::connectFailed)
                .connectionUpHandler(this::connectionUp)
                .connectionDownHandler(this::connectionDown)
                .errorLogHandler(this::logError)
                .requestBackpressureHandler(backpressure::set);

        if (!config.user.isEmpty()) {
            clientConfig.username(config.user).password(config.password);
        }
        if (config.ratelimit != Integer.MAX_VALUE) {
            clientConfig.transactionRateLimit(config.ratelimit);
        }

        client = ClientFactory.createClient(clientConfig);

        periodicStatsContext = client.createStatsContext();
        fullStatsContext = client.createStatsContext();

        System.out.print(HORIZONTAL_RULE);
        System.out.println(" Command Line Configuration");
        System.out.println(HORIZONTAL_RULE);
        System.out.println(config.getConfigDumpString());
    }

    // --- Client2 connection event handlers ---

    void connectFailed(String host, int port) {
        System.err.printf("Connect failed: %s:%d%n", host, port);
    }

    void connectionUp(String host, int port) {
        System.out.printf("Connection up: %s:%d%n", host, port);
    }

    void connectionDown(String host, int port) {
        if ((System.currentTimeMillis() - benchmarkStartTS) < (config.duration * 1000L)) {
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
    void connect(String servers) throws Exception {
        System.out.println("Connecting to the database cluster...");
        client.connectAsync(servers, 120, 10, TimeUnit.SECONDS).get();
    }

    /**
     * Create a Timer task to display performance data on the Vote procedure
     * It calls printStatistics() every displayInterval seconds
     */
    public void schedulePeriodicStats() {
        timer = new Timer();
        TimerTask statsPrinting = new TimerTask() {
            @Override
            public void run() { printStatistics(); }
        };
        timer.scheduleAtFixedRate(statsPrinting,
                                  config.displayinterval * 1000,
                                  config.displayinterval * 1000);
    }

    /**
     * Prints a one line update on performance that can be printed
     * periodically during a benchmark.
     */
    public synchronized void printStatistics() {
        ClientStats stats = periodicStatsContext.fetchAndResetBaseline().getStats();
        long time = Math.round((stats.getEndTimestamp() - benchmarkStartTS) / 1000.0);

        System.out.printf("%02d:%02d:%02d ", time / 3600, (time / 60) % 60, time % 60);
        System.out.printf("Throughput %d/s, ", stats.getTxnThroughput());
        System.out.printf("Aborts/Failures %d/%d",
                stats.getInvocationAborts(), stats.getInvocationErrors());
        System.out.printf("\n");
    }

    /**
     * Prints the results of the voting simulation and statistics
     * about performance.
     *
     * @throws Exception if anything unexpected happens.
     */
    public synchronized void printResults() throws Exception {
        ClientStats stats = fullStatsContext.fetch().getStats();

        // 1. Unique Device ID counting results
        System.out.println("\n" + HORIZONTAL_RULE + " Unique Device ID Counting Results\n" + HORIZONTAL_RULE);
        System.out.printf("A total of %,9d device ids were processed and %d failed (%.2f percent).\n\n",
                totalVotes.get(),
                failedVotes.get(),
                failedVotes.get() * 100.0 / acceptedVotes.get());

        long counts[] = new long[config.appcount];
        double errors[] = new double[config.appcount];
        int nonZeroCount = 0;
        double errorSum = 0;

        for (int appId = 0; appId < config.appcount; appId++) {
            ClientResponse cr = client.callProcedureSync("GetCardEstForApp", appId);
            long estimate = cr.getResults()[0].asScalarLong();
            long count = generator.expectedCountForApp(appId);
            if (estimate == count) {
                nonZeroCount++;
            }
            double percentError = Math.abs(estimate - count) * 100.0 / count;
            errorSum += percentError;
            counts[appId] = count;
            errors[appId] = percentError;
        }

        Arrays.sort(errors);
        double maxError = errors[config.appcount - 1];
        double medianError = errors[config.appcount / 2];
        double meanError = errorSum / config.appcount;

        System.out.printf("The maximum error percentage was: %.2f.\n", maxError);
        System.out.printf("The median error percentage was: %.2f.\n", medianError);
        System.out.printf("The mean error percentage was: %.2f.\n", meanError);
        System.out.printf("The number of apps with perfect estimates was %d or %.2f%%.\n",
                nonZeroCount, nonZeroCount * 100.0 / config.appcount);

        // 3. Performance statistics
        System.out.println("\n" + HORIZONTAL_RULE + " Client Workload Statistics\n" + HORIZONTAL_RULE);

        System.out.printf("Average throughput:            %,9d txns/sec\n", stats.getTxnThroughput());
        System.out.println();
    }

    /**
     * Completion handler for CountDeviceEstimate procedure. Tracks response types.
     */
    void countComplete(ClientResponse response, Throwable throwable) {
        totalVotes.incrementAndGet();
        if (throwable == null && response != null && response.getStatus() == ClientResponse.SUCCESS) {
            acceptedVotes.incrementAndGet();
        }
        else {
            failedVotes.incrementAndGet();
            if (response != null) {
                System.err.println("Procedure returned with error: " + response.getStatusString());
            } else if (throwable != null) {
                System.err.println("Procedure returned with error: " + throwable.getMessage());
            }
            System.err.flush();
        }
    }

    /**
     * Core benchmark code.
     * Connect. Initialize. Run the loop. Cleanup. Print Results.
     *
     * @throws Exception if anything unexpected happens.
     */
    public void runBenchmark() throws Exception {
        System.out.print(HORIZONTAL_RULE);
        System.out.println(" Setup & Initialization");
        System.out.println(HORIZONTAL_RULE);

        // connect to one or more servers, loop until success
        connect(config.servers);

        // Run warmup if configured
        if (config.warmup > 0) {
            System.out.print(HORIZONTAL_RULE);
            System.out.println(" Warming Up");
            System.out.println(HORIZONTAL_RULE);
            System.out.printf("Running warmup for %d seconds...\n", config.warmup);

            final long warmupEndTime = System.currentTimeMillis() + (1000L * config.warmup);
            while (warmupEndTime > System.currentTimeMillis()) {
                while (backpressure.get()) LockSupport.parkNanos(1_000_000); // 1ms
                long[] pair = generator.getNextAppIdAndUniqueDeviceId();
                int appId = (int) pair[0];
                long nextValue = pair[1];
                long hashedValue = MurmurHash.hash64(nextValue);
                client.callProcedureAsync("CountDeviceEstimate",
                                     appId,
                                     hashedValue)
                      .whenComplete(this::countComplete);
            }
            client.drain();
            System.out.println("Warmup complete.\n");
        }

        System.out.print(HORIZONTAL_RULE);
        System.out.println(" Starting Benchmark");
        System.out.println(HORIZONTAL_RULE);

        // reset the stats after warmup
        fullStatsContext.fetchAndResetBaseline();
        periodicStatsContext.fetchAndResetBaseline();
        totalVotes.set(0);
        acceptedVotes.set(0);
        failedVotes.set(0);

        // print periodic statistics to the console
        benchmarkStartTS = System.currentTimeMillis();
        schedulePeriodicStats();

        // Run the benchmark loop for the requested duration
        // The throughput may be throttled depending on client configuration
        System.out.println("\nRunning benchmark...");
        final long benchmarkEndTime = System.currentTimeMillis() + (1000l * config.duration);

        while (benchmarkEndTime > System.currentTimeMillis()) {
            while (backpressure.get()) LockSupport.parkNanos(1_000_000); // 1ms
            long[] pair = generator.getNextAppIdAndUniqueDeviceId();
            int appId = (int) pair[0];
            long nextValue = pair[1];
            long hashedValue = MurmurHash.hash64(nextValue);

            client.callProcedureAsync("CountDeviceEstimate",
                                 appId,
                                 hashedValue)
                  .whenComplete(this::countComplete);
        }

        // cancel periodic stats printing
        timer.cancel();

        // block until all outstanding txns return
        client.drain();

        // print the summary results
        printResults();

        // close down the client connections
        client.close();
    }

    /**
     * Main routine creates a benchmark instance and kicks off the run method.
     *
     * @param args Command line arguments.
     * @throws Exception if anything goes wrong.
     * @see {@link UniqueDevicesConfig}
     */
    public static void main(String[] args) throws Exception {
        // create a configuration from the arguments
        UniqueDevicesConfig config = new UniqueDevicesConfig();
        config.parse(UniqueDevicesClient.class.getName(), args);

        UniqueDevicesClient udc = new UniqueDevicesClient(config);
        udc.runBenchmark();
    }
}
