/* This file is part of VoltDB.
 * Copyright (C) 2008-2026 Volt Active Data Inc.
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
 * Asynchronous benchmark using the Client2 API.
 *
 * Submits Vote stored procedure calls via client.callProcedureAsync and
 * processes responses on completion. The Client2 API is topology-aware,
 * so a single connectAsync call discovers the full cluster.
 *
 * For latency measurements, run SyncBenchmark instead — this benchmark
 * intentionally firehoses the cluster to measure throughput.
 */

package voter.client;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.voltdb.CLIConfig;
import org.voltdb.VoltTable;
import org.voltdb.client.Client2;
import org.voltdb.client.Client2Config;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.ClientStats;
import org.voltdb.client.ClientStatsContext;

public class AsyncBenchmark {

    static final String CONTESTANT_NAMES_CSV =
            "Edwina Burnam,Tabatha Gehling,Kelly Clauss,Jessie Alloway," +
            "Alana Bregman,Jessie Eichman,Allie Rogalski,Nita Coster," +
            "Kurt Walser,Ericka Dieter,Loraine Nygren,Tania Mattioli";

    static final String HORIZONTAL_RULE =
            "----------" + "----------" + "----------" + "----------" +
            "----------" + "----------" + "----------" + "----------" + "\n";

    // Return codes from the Vote stored procedure
    static final long VOTE_SUCCESSFUL = 0;
    static final long ERR_INVALID_CONTESTANT = 1;
    static final long ERR_VOTER_OVER_VOTE_LIMIT = 2;

    final VoterConfig config;
    final Client2 client;

    PhoneCallGenerator switchboard;
    Timer timer;
    long benchmarkStartTS;

    final ClientStatsContext periodicStatsContext;
    final ClientStatsContext fullStatsContext;

    final AtomicLong totalVotes = new AtomicLong(0);
    final AtomicLong acceptedVotes = new AtomicLong(0);
    final AtomicLong badContestantVotes = new AtomicLong(0);
    final AtomicLong badVoteCountVotes = new AtomicLong(0);
    final AtomicLong failedVotes = new AtomicLong(0);

    // Client2 signals backpressure via a notification callback. We mirror the
    // legacy Client1 behavior (which blocked on submission when full) by pausing
    // the submission loop while this flag is true.
    final AtomicBoolean backpressure = new AtomicBoolean(false);

    static class VoterConfig extends CLIConfig {
        @Option(desc = "Interval for performance feedback, in seconds.")
        long displayinterval = 5;

        @Option(desc = "Benchmark duration, in seconds.")
        int duration = 120;

        @Option(desc = "Warmup duration in seconds.")
        int warmup = 2;

        @Option(desc = "Comma separated list of the form server[:port] to connect to.")
        String servers = "localhost";

        @Option(desc = "Number of contestants in the voting contest (from 1 to 10).")
        int contestants = 6;

        @Option(desc = "Maximum number of votes cast per voter.")
        int maxvotes = 2;

        @Option(desc = "Maximum TPS rate for benchmark (default: unlimited).")
        int ratelimit = Integer.MAX_VALUE;

        @Option(desc = "Report latency for async benchmark run.")
        boolean latencyreport = false;

        @Option(desc = "Filename to write raw summary statistics to.")
        String statsfile = "";

        @Option(desc = "User name for connection.")
        String user = "";

        @Option(desc = "Password for connection.")
        String password = "";

        @Option(desc = "Enable SSL; optionally provide property file with truststore config.")
        String sslfile = "";

        @Override
        public void validate() {
            if (duration <= 0) exitWithMessageAndUsage("duration must be > 0");
            if (warmup < 0) exitWithMessageAndUsage("warmup must be >= 0");
            if (displayinterval <= 0) exitWithMessageAndUsage("displayinterval must be > 0");
            if (contestants <= 0) exitWithMessageAndUsage("contestants must be > 0");
            if (maxvotes <= 0) exitWithMessageAndUsage("maxvotes must be > 0");
            if (ratelimit <= 0) exitWithMessageAndUsage("ratelimit must be > 0");
        }
    }

    public AsyncBenchmark(VoterConfig config) {
        this.config = config;

        // Client2 throughput tuning for firehose benchmark behavior:
        //  - clientRequestLimit (default 1000): exceeding it fails the future with
        //    RequestLimitException. Lifted so queuing never fails.
        //  - outstandingTransactionLimit (default 100): caps how many sends the
        //    internal sender thread can have in flight — the real throughput gate.
        //    Lifted so the sender isn't permit-starved.
        // Flow control comes from the requestBackpressureHandler callback that
        // pauses our submission loop when the cluster signals it's overwhelmed.
        // 20k was empirically faster than MAX_VALUE (less atomic bookkeeping
        // overhead inside the Semaphore for typical workloads).
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
        if (!config.sslfile.trim().isEmpty()) {
            clientConfig.trustStoreFromPropertyFile(config.sslfile).enableSSL();
        }
        if (config.ratelimit != Integer.MAX_VALUE) {
            clientConfig.transactionRateLimit(config.ratelimit);
        }

        client = ClientFactory.createClient(clientConfig);

        periodicStatsContext = client.createStatsContext();
        fullStatsContext = client.createStatsContext();

        switchboard = new PhoneCallGenerator(config.contestants);

        System.out.print(HORIZONTAL_RULE);
        System.out.println(" Command Line Configuration");
        System.out.println(HORIZONTAL_RULE);
        System.out.println(config.getConfigDumpString());
        if (config.latencyreport) {
            System.out.println("NOTICE: latencyreport is ON for async run; throughput will be lower.\n");
        }
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

    public synchronized void printStatistics() {
        ClientStats stats = periodicStatsContext.fetchAndResetBaseline().getStats();
        long time = Math.round((stats.getEndTimestamp() - benchmarkStartTS) / 1000.0);

        System.out.printf("%02d:%02d:%02d ", time / 3600, (time / 60) % 60, time % 60);
        System.out.printf("Throughput %d/s, ", stats.getTxnThroughput());
        System.out.printf("Aborts/Failures %d/%d",
                stats.getInvocationAborts(), stats.getInvocationErrors());
        if (config.latencyreport) {
            System.out.printf(", Avg/95%% Latency %.2f/%.2fms", stats.getAverageLatency(),
                    stats.kPercentileLatencyAsDouble(0.95));
        }
        System.out.println();
    }

    public synchronized void printResults() throws Exception {
        ClientStats stats = fullStatsContext.fetch().getStats();

        String display = "\n" +
                         HORIZONTAL_RULE +
                         " Voting Results\n" +
                         HORIZONTAL_RULE +
                         "\nA total of %,9d votes were received during the benchmark...\n" +
                         " - %,9d Accepted\n" +
                         " - %,9d Rejected (Invalid Contestant)\n" +
                         " - %,9d Rejected (Maximum Vote Count Reached)\n" +
                         " - %,9d Failed (Transaction Error)\n\n";
        System.out.printf(display, totalVotes.get(),
                acceptedVotes.get(), badContestantVotes.get(),
                badVoteCountVotes.get(), failedVotes.get());

        VoltTable result = client.callProcedureSync("Results").getResults()[0];

        System.out.println("Contestant Name\t\tVotes Received");
        while (result.advanceRow()) {
            System.out.printf("%s\t\t%,14d%n", result.getString(0), result.getLong(2));
        }
        System.out.printf("%nThe Winner is: %s%n%n", result.fetchRow(0).getString(0));

        System.out.print(HORIZONTAL_RULE);
        System.out.println(" Client Workload Statistics");
        System.out.println(HORIZONTAL_RULE);

        System.out.printf("Average throughput:            %,9d txns/sec%n", stats.getTxnThroughput());
        if (config.latencyreport) {
            System.out.printf("Average latency:               %,9.2f ms%n", stats.getAverageLatency());
            System.out.printf("10th percentile latency:       %,9.2f ms%n", stats.kPercentileLatencyAsDouble(.1));
            System.out.printf("50th percentile latency:       %,9.2f ms%n", stats.kPercentileLatencyAsDouble(.5));
            System.out.printf("95th percentile latency:       %,9.2f ms%n", stats.kPercentileLatencyAsDouble(.95));
            System.out.printf("99th percentile latency:       %,9.2f ms%n", stats.kPercentileLatencyAsDouble(.99));
            System.out.printf("99.9th percentile latency:     %,9.2f ms%n", stats.kPercentileLatencyAsDouble(.999));

            System.out.print("\n" + HORIZONTAL_RULE);
            System.out.println(" Latency Histogram");
            System.out.println(HORIZONTAL_RULE);
            System.out.println(stats.latencyHistoReport());
        }
    }

    /**
     * Completion handler for a Vote procedure call.
     * Replaces the legacy ProcedureCallback — Client2 uses CompletableFuture.
     */
    void voteComplete(ClientResponse response, Throwable throwable) {
        totalVotes.incrementAndGet();
        if (throwable != null || response == null || response.getStatus() != ClientResponse.SUCCESS) {
            failedVotes.incrementAndGet();
            return;
        }
        long resultCode = response.getResults()[0].asScalarLong();
        if (resultCode == ERR_INVALID_CONTESTANT) {
            badContestantVotes.incrementAndGet();
        } else if (resultCode == ERR_VOTER_OVER_VOTE_LIMIT) {
            badVoteCountVotes.incrementAndGet();
        } else {
            assert (resultCode == VOTE_SUCCESSFUL);
            acceptedVotes.incrementAndGet();
        }
    }

    public void runBenchmark() throws Exception {
        System.out.print(HORIZONTAL_RULE);
        System.out.println(" Setup & Initialization");
        System.out.println(HORIZONTAL_RULE);

        connect(config.servers);

        System.out.println("\nPopulating Static Tables\n");
        client.callProcedureSync("Initialize", config.contestants, CONTESTANT_NAMES_CSV);

        System.out.print(HORIZONTAL_RULE);
        System.out.println(" Starting Benchmark");
        System.out.println(HORIZONTAL_RULE);

        // Warmup — results discarded
        System.out.println("Warming up...");
        long warmupEndTime = System.currentTimeMillis() + (1000L * config.warmup);
        while (warmupEndTime > System.currentTimeMillis()) {
            while (backpressure.get()) LockSupport.parkNanos(1_000_000); // 1ms
            PhoneCallGenerator.PhoneCall call = switchboard.receive();
            client.callProcedureAsync("Vote",
                    call.phoneNumber, call.contestantNumber, config.maxvotes);
        }

        // Reset stats after warmup
        fullStatsContext.fetchAndResetBaseline();
        periodicStatsContext.fetchAndResetBaseline();

        benchmarkStartTS = System.currentTimeMillis();
        schedulePeriodicStats();

        // Measured phase
        System.out.println("\nRunning benchmark...");
        long benchmarkEndTime = System.currentTimeMillis() + (1000L * config.duration);
        while (benchmarkEndTime > System.currentTimeMillis()) {
            while (backpressure.get()) LockSupport.parkNanos(1_000_000); // 1ms
            PhoneCallGenerator.PhoneCall call = switchboard.receive();
            CompletableFuture<ClientResponse> fut = client.callProcedureAsync("Vote",
                    call.phoneNumber, call.contestantNumber, config.maxvotes);
            fut.whenComplete(this::voteComplete);
        }

        timer.cancel();

        // Wait for outstanding async calls to complete
        client.drain();

        printResults();
        client.close();
    }

    public static void main(String[] args) throws Exception {
        VoterConfig config = new VoterConfig();
        config.parse(AsyncBenchmark.class.getName(), args);

        AsyncBenchmark benchmark = new AsyncBenchmark(config);
        benchmark.runBenchmark();
    }
}
