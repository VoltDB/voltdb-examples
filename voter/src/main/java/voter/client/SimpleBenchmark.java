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

package voter.client;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.voltdb.client.Client2;
import org.voltdb.client.Client2Config;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;

/**
 * Simple synchronous benchmark using the Client2 API.
 * Inserts TXNS rows into the VOTES table.
 */
public class SimpleBenchmark {

    private static final int TXNS = 10000;

    public static void main(String[] args) throws Exception {
        System.out.println("Running Simple Benchmark");

        String servers = args.length > 0 ? String.join(",", args) : "localhost";

        Client2Config config = new Client2Config();
        try (Client2 client = ClientFactory.createClient(config)) {
            client.connectAsync(servers, 120, 10, TimeUnit.SECONDS).get();

            Random rng = new Random();
            for (int i = 0; i < TXNS; i++) {
                ClientResponse response = client.callProcedureSync(
                        "VOTES.insert", rng.nextLong(), "MA", Integer.valueOf(i));

                if (response.getStatus() != ClientResponse.SUCCESS) {
                    throw new RuntimeException(response.getStatusString());
                }

                if (i % 1000 == 0) {
                    System.out.print(".");
                }
            }
        }

        System.out.println(" completed " + TXNS + " transactions.");
    }
}
