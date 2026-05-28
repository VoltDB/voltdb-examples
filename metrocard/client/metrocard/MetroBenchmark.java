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

package metrocard;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import org.voltdb.CLIConfig;
import org.voltdb.VoltTable;
import org.voltdb.client.Client2;
import org.voltdb.client.Client2Config;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.ClientStats;
import org.voltdb.client.ClientStatsContext;
import org.voltdb.types.TimestampType;

import com.google.common.collect.ConcurrentHashMultiset;
import com.google.common.collect.Multiset;

public class MetroBenchmark {

    // handy, rather than typing this out several times
    public static final String HORIZONTAL_RULE =
            "----------" + "----------" + "----------" + "----------" +
            "----------" + "----------" + "----------" + "----------" + "\n";

    // validated command line configuration
    final MetroCardConfig config;
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

    // for random data generation
    private RandomCollection<Integer> stations = new RandomCollection<Integer>();
    int[] balances = {5000,2000,1000,500};
    Calendar cal = Calendar.getInstance();
    int cardCount = 0;
    int max_station_id = 0;

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
    public static class MetroCardConfig extends CLIConfig {

        // STANDARD BENCHMARK OPTIONS
        @Option(desc = "Comma separated list of the form server[:port] to connect to.")
        String servers = "localhost";

        @Option(desc = "User name")
        public String user = "";

        @Option(desc = "Password")
        public String password = "";

        @Option(desc = "Benchmark duration, in seconds.")
        int duration = 20;

        @Option(desc = "Interval for performance feedback, in seconds.")
        long displayinterval = 5;

        @Option(desc = "Warmup duration in seconds.")
        int warmup = 2;

        @Option(desc = "Maximum TPS rate for benchmark.")
        int ratelimit = Integer.MAX_VALUE;

        @Option(desc = "Filename to write raw summary statistics to.")
        String statsfile = "";

        // CUSTOM OPTIONS
        @Option(desc = "Number of Cards")
        int cardcount = 500000;

        @Override
        public void validate() {
            if (duration <= 0) exitWithMessageAndUsage("duration must be > 0");
            if (warmup < 0) exitWithMessageAndUsage("warmup must be >= 0");
            if (displayinterval <= 0) exitWithMessageAndUsage("displayinterval must be > 0");
            if (ratelimit <= 0) exitWithMessageAndUsage("ratelimit must be > 0");
        }
    }

    public static class RandomCollection<E> {
        private final NavigableMap<Double, E> map = new TreeMap<Double, E>();
        private final Random random;
        private double total = 0;

        public RandomCollection() {
            this(new Random());
        }

        public RandomCollection(Random random) {
            this.random = random;
        }

        public void add(double weight, E result) {
            if (weight <= 0) return;
            total += weight;
            map.put(total, result);
        }

        public E next() {
            double value = random.nextDouble() * total;
            return map.ceilingEntry(value).getValue();
        }
    }

    // constructor
    public MetroBenchmark(MetroCardConfig config) {
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

        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

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

        private static Multiset<String> stats = ConcurrentHashMultiset.create();
        private static ConcurrentHashMap<String,Integer> procedures = new ConcurrentHashMap<String,Integer>();
        String procedureName;
        long maxErrors;

        public static int count( String procedureName, String event ){
            return stats.add(procedureName + event, 1);
        }

        public static int getCount( String procedureName, String event ){
            return stats.count(procedureName + event);
        }

        public static void printProcedureResults(String procedureName) {
            System.out.println("  " + procedureName);
            System.out.println("        calls: " + getCount(procedureName,"call"));
            System.out.println("      commits: " + getCount(procedureName,"commit"));
            System.out.println("    rollbacks: " + getCount(procedureName,"rollback"));
        }

        public static void printAllResults() {
        List<String> l = new ArrayList<String>(procedures.keySet());
        Collections.sort(l);
        for (String e : l) {
            printProcedureResults(e);
        }
        }

        public BenchmarkCallback(String procedure, long maxErrors) {
            super();
            this.procedureName = procedure;
            this.maxErrors = maxErrors;
        procedures.putIfAbsent(procedure,1);
        }

        public BenchmarkCallback(String procedure) {
            this(procedure, 5l);
        }

        /**
         * Completion handler — designed to be used as a {@link java.util.function.BiConsumer}
         * for {@link java.util.concurrent.CompletableFuture#whenComplete}.
         */
        public void complete(ClientResponse cr, Throwable throwable) {

            count(procedureName,"call");

            if (throwable == null && cr != null && cr.getStatus() == ClientResponse.SUCCESS) {
                count(procedureName,"commit");
            } else {
                long totalErrors = count(procedureName,"rollback");

                if (totalErrors > maxErrors) {
                    System.err.println("exceeded " + maxErrors + " maximum database errors - exiting client");
                    System.exit(-1);
                }

                if (throwable != null) {
                    System.err.println("DATABASE ERROR: " + throwable.getMessage());
                } else if (cr != null) {
                    System.err.println("DATABASE ERROR: " + cr.getStatusString());
                }
            }
        }
    }

    public void initialize() throws Exception {

        VoltTable stationTable = client.callProcedureSync("@AdHoc","SELECT * FROM mc_stations ORDER BY station_id DESC;").getResults()[0];

        if (stationTable.getRowCount() == 0) {
            System.err.println("Station data not loaded. Please load station data before using this example.");
            System.exit(-1);
        }

        while (stationTable.advanceRow()) {
            double weight = stationTable.getLong("weight");
            int id = (int) stationTable.getLong("station_id");
            stations.add(weight, id);
        }

        // assume sorted
        max_station_id = (int) stationTable.fetchRow(0).getLong("station_id");

        // generate cards
        System.out.println("Generating " + config.cardcount + " cards...");

        // check if cards already initialized
        cardCount = client.callProcedureSync("@AdHoc","SELECT COUNT(*) FROM mc_cards;").getResults()[0].getRowCount();

        for (int i=cardCount; i<config.cardcount; i++) {
            generateCard();
            if (i+1 % 5000 == 0)
                System.out.println("  " + i);
        }
    }

    public int randomizeNotify() throws Exception {
        // create a small number of text and email notification
        // preferences, settable via random weighting below
        float n = rand.nextFloat();
        if (n > 0.01) {
            return(0);
        }
        if (n > 0.005) {
            return(1);
        }
        return(2);
    }

    public void generateCard() throws Exception {

        // default card (pay per fare)
        int enabled = 1;
        int card_type = 0;
        int balance = balances[rand.nextInt(balances.length)];
        String preName = "T Ryder ";
        String phone = "6174567890";
        String email = "tryder@gmail.com";
        int notify = randomizeNotify();
        TimestampType expires = null;

        // disable 1/10000 cards
        if (rand.nextInt(10000) == 0)
            enabled = 0;

        // make 1/3 cards unlimited (weekly or monthly pass)
        if (rand.nextInt(3) == 0) {
            card_type = 1;
            balance = 0;
            // expired last night at midnight, or any of the next 30 days
            Calendar cal2 = (Calendar)cal.clone();
            cal2.add(Calendar.DATE,rand.nextInt(30));
            expires = new TimestampType(cal2.getTime());
        }

        BenchmarkCallback cb = new BenchmarkCallback("MC_CARDS.upsert");
        client.callProcedureAsync("MC_CARDS.upsert",
                             ++cardCount,
                             enabled,
                             card_type,
                             balance,
                             expires,
                             preName + cardCount, // create synthetic numeric person
                             phone,
                             email,
                             notify)
              .whenComplete(cb::complete);

    }

    public void iterate() throws Exception {

        // sometimes create a new card
        if (rand.nextInt(25) == 0)
            generateCard();

        // sometimes replenish a card
        if (rand.nextInt(5) == 0) {
            BenchmarkCallback cb = new BenchmarkCallback("mc_ReplenishCard");
            client.callProcedureAsync("mc_ReplenishCard",
                                 balances[rand.nextInt(balances.length)],
                                 rand.nextInt(cardCount)
                                 )
                  .whenComplete(cb::complete);
        }

        // card swipe
        int card_id = rand.nextInt(cardCount+1000); // use +1000 so sometimes we get an invalid card_id

        int station_id = 0;
        if (rand.nextInt(5) == 0) {
            station_id = rand.nextInt(max_station_id); // sometimes pick a random station
        } else {
            station_id = stations.next(); // pick a station based on the weights
        }

        BenchmarkCallback cb = new BenchmarkCallback("McCardSwipe");
        client.callProcedureAsync("McCardSwipe",
                             card_id,
                             station_id
                             )
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
        MetroCardConfig config = new MetroCardConfig();
        config.parse(MetroBenchmark.class.getName(), args);

        MetroBenchmark benchmark = new MetroBenchmark(config);
        benchmark.runBenchmark();

    }
}
