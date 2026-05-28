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
 * This class can be customized for simple tests or micro-benchmarks by modifying the
 * benchmarkItem method which generates random parameter values and calls a procedure
 *
 */
package simple;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.voltdb.client.Client2;
import org.voltdb.client.Client2Config;
import org.voltdb.client.ClientFactory;
import simple.util.BenchmarkCallback;
import simple.util.BenchmarkStats;

public class Benchmark {

    private Client2 client;
    private BenchmarkStats stats;
    private Random rand = new Random();
    private int benchmarkSize;

    public Benchmark(String servers, int size) throws Exception {

        this.benchmarkSize = size;

        // create a Client2 instance using default settings
        client = ClientFactory.createClient(new Client2Config());

        // connect to each server listed (separated by commas) — Client2 is topology-aware
        client.connectAsync(servers, 120, 10, TimeUnit.SECONDS).get();

        // This helper class is used to capture client statistics
        stats = new BenchmarkStats(client, false);
    }


    /*
     * ----------------------------------------------------------------------
     * Customize this method for your own use case:
     *   1. generate random parameter values
     *   2. call a procedure
     * ----------------------------------------------------------------------
     */
    public void benchmarkItem() throws Exception {

        // BenchmarkCallback tracks the transaction results for the given procedure name,
        // which should match the procedure called below.
        BenchmarkCallback callback = new BenchmarkCallback("insert_session");

        // generate some random parameter values
        int appid = rand.nextInt(50);
        int deviceid = rand.nextInt(1000000);

        // call the procedure asynchronously, attaching the callback via whenComplete
        client.callProcedureAsync("insert_session",
                             appid,
                             deviceid
                             )
              .whenComplete(callback::complete);

    }


    public void runBenchmark() throws Exception {

        // print a heading
        String dashes = new String(new char[80]).replace("\0", "-");
        System.out.println(dashes);
        System.out.println(" Running Performance Benchmark for " + benchmarkSize + " Transactions");
        System.out.println(dashes);

        // start recording statistics for the benchmark, outputting every 5 seconds
        stats.startBenchmark();

        // main loop for the benchmark
        for (int i=0; i<benchmarkSize; i++) {

            benchmarkItem();

        }

        // stop recording, print stats
        stats.endBenchmark();

        // wait for any outstanding responses to return before closing the client
        client.drain();
        client.close();

        // print the transaction results tracked by BenchmarkCallback
        BenchmarkCallback.printAllResults();
    }


    public static void main(String[] args) throws Exception {

        // the first parameter can be a comma-separated list of hostnames or IPs
        String serverlist = "localhost";
        if (args.length > 0) {
            serverlist = args[0];
        }

        // the second parameter can be the number of transactions to execute
        int transactions = 5000000;
        if (args.length > 1) {
            transactions = Integer.parseInt(args[1]);
        }

        Benchmark benchmark = new Benchmark(serverlist, transactions);
        benchmark.runBenchmark();

    }
}
