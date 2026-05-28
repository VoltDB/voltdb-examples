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

package bankoffers;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import org.voltdb.CLIConfig;
import org.voltdb.client.Client2;
import org.voltdb.client.Client2Config;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.ClientStats;
import org.voltdb.client.ClientStatsContext;
import org.voltdb.types.TimestampType;

import com.google.common.collect.ConcurrentHashMultiset;
import com.google.common.collect.Multiset;

public class OfferBenchmark {

    // handy, rather than typing this out several times
    public static final String HORIZONTAL_RULE =
            "----------" + "----------" + "----------" + "----------" +
            "----------" + "----------" + "----------" + "----------" + "\n";

    // validated command line configuration
    final BankOffersConfig config;
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

    private Random rand = new Random();
    private long txnId = 0;
    private Long[] accounts;
    private String[] acct_states;
    private int[] amounts = { 25, 50, 75, 100, 150, 200, 250, 300 };
    private PersonGenerator gen = new PersonGenerator();
    private String[] offers = {
            "$5 off any purchase over $25",
            "20% off any purchase over $50",
            "Extra 25% off sale items" };

    /**
     * Prints headings
     */
    public static void printHeading(String heading) {
        System.out.print("\n"+HORIZONTAL_RULE);
        System.out.println(" " + heading);
        System.out.println(HORIZONTAL_RULE);
    }

    /**
     * Uses CLIConfig class to declaratively state command line options
     * with defaults and validation.
     */
    public static class BankOffersConfig extends CLIConfig {

        // STANDARD BENCHMARK OPTIONS
        @Option(desc = "Comma separated list of the form server[:port] to connect to.")
        String servers = "localhost";

        @Option(desc = "User name for connection.")
        public String user = "";

        @Option(desc = "Password for connection.")
        public String password = "";

        @Option(desc = "Benchmark duration, in seconds.")
        int duration = 20;

        @Option(desc = "Interval for performance feedback, in seconds.")
        long displayinterval = 5;

        @Option(desc = "Warmup duration in seconds.")
        int warmup = 2;

        @Option(desc = "Maximum TPS rate for benchmark.")
        int ratelimit = 100000;

        @Option(desc = "Filename to write raw summary statistics to.")
        String statsfile = "";

        // CUSTOM OPTIONS
        @Option(desc = "Number of customers to generate")
        int custcount = 100000;

        @Option(desc = "Number of vendors to generate")
        int vendorcount = 5000;

        @Override
        public void validate() {
            if (duration <= 0) exitWithMessageAndUsage("duration must be > 0");
            if (warmup < 0) exitWithMessageAndUsage("warmup must be >= 0");
            if (displayinterval <= 0) exitWithMessageAndUsage("displayinterval must be > 0");
            if (ratelimit <= 0) exitWithMessageAndUsage("ratelimit must be > 0");
        }
    }

    // constructor
    public OfferBenchmark(BankOffersConfig config) {
        this.config = config;

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

        printHeading("Command Line Configuration");
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
        System.out.printf("Aborts/Failures %d/%d, ",
                stats.getInvocationAborts(), stats.getInvocationErrors());

        // cast to stats.getAverageLatency from long to double
        System.out.printf("Avg/95%% Latency %.2f/%dms\n",
                          stats.getAverageLatency(),
                          stats.kPercentileLatency(0.95));

    }

    /**
     * Prints the results of the voting simulation and statistics
     * about performance.
     *
     * @throws Exception if anything unexpected happens.
     */
    public synchronized void printResults() throws Exception {
        printHeading("Transaction Results");
        BenchmarkCallback.printAllResults();

        ClientStats stats = fullStatsContext.fetch().getStats();

        // 3. Performance statistics
        printHeading("Client Workload Statistics");

        System.out.printf("Average throughput:            %,9d txns/sec\n", stats.getTxnThroughput());
        System.out.printf("Average latency:               %,9.2f ms\n", stats.getAverageLatency());
        System.out.printf("95th percentile latency:       %,9d ms\n", stats.kPercentileLatency(.95));
        System.out.printf("99th percentile latency:       %,9d ms\n", stats.kPercentileLatency(.99));

        printHeading("System Server Statistics");

        System.out.printf("Reported Internal Avg Latency: %,9.2f ms\n", stats.getAverageInternalLatency());
    }

    public static class BenchmarkCallback {

        private static Multiset<String> calls = ConcurrentHashMultiset.create();
        private static Multiset<String> commits = ConcurrentHashMultiset.create();
        private static Multiset<String> rollbacks = ConcurrentHashMultiset.create();

        String procedureName;
        long maxErrors;

        public static void printProcedureResults(String procedureName) {
            System.out.println("  " + procedureName);
            System.out.println("        calls: " + calls.count(procedureName));
            System.out.println("      commits: " + commits.count(procedureName));
            System.out.println("    rollbacks: " + rollbacks.count(procedureName));
        }

        public static void printAllResults() {
        for (String e : calls.elementSet()) {
            printProcedureResults(e);
        }
        }

        public BenchmarkCallback(String procedure, long maxErrors) {
            super();
            this.procedureName = procedure;
            this.maxErrors = maxErrors;
        }

        public BenchmarkCallback(String procedure) {
            this(procedure, 5l);
        }

        /**
         * Completion handler — designed to be used as a {@link java.util.function.BiConsumer}
         * for {@link java.util.concurrent.CompletableFuture#whenComplete}.
         */
        public void complete(ClientResponse cr, Throwable throwable) {

            calls.add(procedureName,1);

            if (throwable == null && cr != null && cr.getStatus() == ClientResponse.SUCCESS) {
                commits.add(procedureName,1);
            } else {
                long totalErrors = rollbacks.add(procedureName,1);

                if (throwable != null) {
                    System.err.println("DATABASE ERROR: " + throwable.getMessage());
                } else if (cr != null) {
                    System.err.println("DATABASE ERROR: " + cr.getStatusString());
                }

                if (totalErrors > maxErrors) {
                    System.err.println("exceeded " + maxErrors + " maximum database errors - exiting client");
                    System.exit(-1);
                }

            }
        }
    }

    // this gets run once before the benchmark begins
    public void initialize() throws Exception {

        List<Long> acctList = new ArrayList<Long>(config.custcount*2);
        List<String> stList = new ArrayList<String>(config.custcount*2);

        // generate customers
        System.out.println("generating " + config.custcount + " customers...");
        for (int c=0; c<config.custcount; c++) {

            if (c % 10000 == 0) {
                System.out.println("  "+c);
            }

            PersonGenerator.Person p = gen.newPerson();
            //int ac = rand.nextInt(areaCodes.length);

            // Honor Client2 backpressure during the bulk insert loop, otherwise
            // we fire millions of async procedure calls and blow through the
            // clientRequestLimit(20_000) before VoltDB can drain them.
            while (backpressure.get()) LockSupport.parkNanos(1_000_000); // 1ms

            BenchmarkCallback cb = new BenchmarkCallback("CUSTOMER.insert");
            client.callProcedureAsync("CUSTOMER.insert",
                                 c,
                                 p.firstname,
                                 p.lastname,
                                 "Anytown",
                                 p.state,
                                 p.phonenumber,
                                 p.dob,
                                 p.sex
                                 )
                  .whenComplete(cb::complete);

            int accts = rand.nextInt(5);
            for (int a=0; a<accts; a++) {

                int acct_no = (c*100)+a;

                // Same backpressure guard for the nested account-insert loop.
                while (backpressure.get()) LockSupport.parkNanos(1_000_000); // 1ms

                BenchmarkCallback acb = new BenchmarkCallback("ACCOUNT.insert");
                client.callProcedureAsync("ACCOUNT.insert",
                                     acct_no,
                                     c,
                                     rand.nextInt(10000),
                                     rand.nextInt(10000),
                                     new Date(),
                                     "Y"
                                     )
                      .whenComplete(acb::complete);
                acctList.add(Long.valueOf(acct_no));
                stList.add(p.state);
            }
        }

        accounts = acctList.toArray(new Long[acctList.size()]);
        acct_states = stList.toArray(new String[stList.size()]);

        // generate vendor offers
        System.out.println("generating " + config.vendorcount + " vendors...");
        for (int v = 0; v < config.vendorcount; v++) {
            if (v % 10000 == 0) {
                System.out.println("  " + v);
            }

            // Same backpressure guard as the customer/account loops above.
            while (backpressure.get()) LockSupport.parkNanos(1_000_000); // 1ms

            BenchmarkCallback cb = new BenchmarkCallback("VENDOR_OFFERS.insert");
            client.callProcedureAsync("VENDOR_OFFERS.insert",
                                 v,
                                 rand.nextInt(5) + 1,
                                 0,
                                 rand.nextInt(5) + 1,
                                 (double) rand.nextInt(100),
                                 0,
                                 offers[rand.nextInt(offers.length)]
                                 )
                  .whenComplete(cb::complete);
        }
    }

    public void iterate() throws Exception {

        // pick a random account and generate a transaction
        int i = rand.nextInt(accounts.length);
        txnId++;
        long acctNo = accounts[i];
        double txnAmt = amounts[rand.nextInt(amounts.length)];
        String txnState = acct_states[i];
        String txnCity = "Some City";
        TimestampType txnTS = new TimestampType();
        int vendorId = rand.nextInt(config.vendorcount);
        // generate "out of state" fraud
        // a small % of the time, use a random state
        if (rand.nextInt(50000) == 0) {
            txnState = PersonGenerator.randomState();
        }

        BenchmarkCallback cb = new BenchmarkCallback("CheckForOffers");
        client.callProcedureAsync("CheckForOffers",
                             txnId,acctNo,txnAmt,txnState,txnCity,txnTS,vendorId)
              .whenComplete(cb::complete);

    }

    /**
     * Core benchmark code.
     * Connect. Initialize. Run the loop. Cleanup. Print Results.
     *
     * @throws Exception if anything unexpected happens.
     */
    public void runBenchmark() throws Exception {
        printHeading("Setup & Initialization");

        // connect to one or more servers, loop until success
        connect(config.servers);

        // initialize using synchronous call
        System.out.println("\nPre-loading Tables...\n");
        initialize();

        // Run the benchmark loop for the requested warmup time
        // The throughput may be throttled depending on client configuration
        System.out.println("Warming up for the specified "+ config.warmup +" seconds...");
        final long warmupEndTime = System.currentTimeMillis() + (1000l * config.warmup);
        while (warmupEndTime > System.currentTimeMillis()) {
            while (backpressure.get()) LockSupport.parkNanos(1_000_000); // 1ms
            iterate();
        }

        printHeading("Starting Benchmark");

        // reset the stats after warmup
        fullStatsContext.fetchAndResetBaseline();
        periodicStatsContext.fetchAndResetBaseline();

        // print periodic statistics to the console
        benchmarkStartTS = System.currentTimeMillis();
        schedulePeriodicStats();

        // Run the benchmark loop for the requested duration
        // The throughput may be throttled depending on client configuration
        System.out.println("\nRunning benchmark...");
        final long benchmarkEndTime = System.currentTimeMillis() + (1000l * config.duration);
        while (benchmarkEndTime > System.currentTimeMillis()) {
            while (backpressure.get()) LockSupport.parkNanos(1_000_000); // 1ms
            iterate();
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

    public static void main(String[] args) throws Exception {
        BankOffersConfig config = new BankOffersConfig();
        config.parse(OfferBenchmark.class.getName(), args);

        OfferBenchmark c = new OfferBenchmark(config);
        c.runBenchmark();
    }


}
