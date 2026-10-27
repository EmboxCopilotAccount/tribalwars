package se.oscarwiklund.twlan2.backend.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.locks.ReentrantLock;

// One lock for every change to the game: API requests (GameLockFilter), the game tick, each NPC step and the sweeps.
// Villages have no version column and are written whole-value (resources settled to an absolute amount), so two
// writers working on the same village at once lost one of the changes: parallel recruit orders all passed the farm
// check and only one was paid for, a market send was undone by the tick delivering loot to the same village (#124).
// Held until the writer's transaction has committed, so the next one always starts from committed data.
// Fair, so the tick is not starved by a stream of requests.
@Component
public class GameLock {

    private final ReentrantLock lock = new ReentrantLock(true);

    // For code outside a transaction that calls transactional services: the lock covers their commits.
    public void run(Runnable body) {
        lock.lock();
        try {
            body.run();
        } finally {
            lock.unlock();
        }
    }

    // First line of a @Transactional method: held from here until that transaction has committed or rolled back.
    public void holdForTransaction() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("GameLock.holdForTransaction needs an active transaction");
        }
        lock.lock();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                lock.unlock();
            }
        });
    }

    public boolean isHeldByCurrentThread() { return lock.isHeldByCurrentThread(); }

    public boolean hasQueuedThreads() { return lock.hasQueuedThreads(); }
}
