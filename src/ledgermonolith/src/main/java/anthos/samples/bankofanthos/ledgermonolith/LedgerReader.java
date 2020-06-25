/*
 * Copyright 2020, Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package anthos.samples.bankofanthos.ledgermonolith;

import java.util.logging.Logger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

/**
 * Defines an interface for reacting to new transactions
 *
 * @param transaction  the full transaction object
 */
interface LedgerReaderCallback {
    void processTransaction(Transaction transaction);
}

/**
 * LedgerReader listens for and reacts to incoming transactions
 */
@Component
public final class LedgerReader {

    private static final Logger LOGGER =
            Logger.getLogger(LedgerReader.class.getName());

    @Autowired
    private TransactionRepository dbRepo;

    @Value("${POLL_MS:100}")
    private Integer pollMs;
    @Value("${LOCAL_ROUTING_NUM}")
    private String localRoutingNum;

    private Thread backgroundThread;
    private LedgerReaderCallback callback;
    private long latestId = -1;

    /**
     * LedgerReader setup
     * Synchronously loads all existing transactions, and then starts
     * a background thread to listen for future transactions
     *
     * @param callback to process transactions
     * @throws IllegalStateException if callback is null
     */
    public void startWithCallback(LedgerReaderCallback callback)
            throws IllegalStateException {
        if (callback == null) {
            throw new IllegalStateException("callback is null");
        }
        this.callback = callback;
        // get the latest transaction id in ledger
        try {
            this.latestId = dbRepo.latestId();
            LOGGER.info(String.format("starting id: %d", latestId));
        } catch (ResourceAccessException
                | DataAccessResourceFailureException e) {
            LOGGER.warning("Could not contact ledger database at init");
        }
        this.backgroundThread = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean alive = true;
                while (alive) {
                    // sleep between polls
                    try {
                        Thread.sleep(pollMs);
                    } catch (InterruptedException e) {
                        LOGGER.warning("LedgerReader sleep interrupted");
                    }
                    // check for new updates in ledger
                    Long remoteLatest;
                    try {
                        remoteLatest = dbRepo.latestId();
                    } catch (ResourceAccessException
                            | DataAccessResourceFailureException e) {
                        remoteLatest = latestId;
                        LOGGER.warning("Could not reach ledger database");
                    }
                    // if there are new transactions, poll the database
                    if (remoteLatest > latestId) {
                        latestId = pollTransactions(latestId);
                    } else if (remoteLatest < latestId) {
                        // remote database out of sync
                        // suspend processing transactions to reset service
                        alive = false;
                        LOGGER.severe("remote transaction id out of sync");
                    }
                }
            }
        });
        LOGGER.info("Starting background thread.");
        this.backgroundThread.start();
    }

    /**
     * Poll for new transactions
     * Execute callback for each one
     *
     * @param startingId the transaction to start reading after.
     *                            -1 = start reading at beginning of the ledger
     * @return long id of latest transaction processed
     */
    private long pollTransactions(long startingId) {
        long latestId = startingId;
        Iterable<Transaction> transactionList = dbRepo.findLatest(startingId);

        for (Transaction transaction : transactionList) {
            callback.processTransaction(transaction);
            latestId = transaction.getTransactionId();
        }
        return latestId;
    }

    /**
     * Indicates health of LedgerReader
     * @return false if background thread dies
     */
    public boolean isAlive() {
        return backgroundThread == null || backgroundThread.isAlive();
    }
}