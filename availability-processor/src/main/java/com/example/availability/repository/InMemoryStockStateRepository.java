package com.example.availability.repository;

import com.example.availability.model.StockKey;
import com.example.availability.model.StockState;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * In-memory implementation, sufficient for the exercise (state is lost on restart). In production
 * this would be a database table keyed by the stock key with a conditional write
 * ({@code UPDATE ... WHERE applied_version < :version})!!.
 *
 * <p>Locks are striped: a fixed array of locks indexed by the key hash. Memory stays bounded no
 * matter how many keys we see; two unrelated keys may occasionally share a stripe, which only
 * costs a little parallelism, never correctness.
 */
@ApplicationScoped
public class InMemoryStockStateRepository implements StockStateRepository {

    private static final int LOCK_STRIPES = 1024;

    private final ConcurrentHashMap<StockKey, StockState> states = new ConcurrentHashMap<>();
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];

    public InMemoryStockStateRepository() {
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    @Override
    public Optional<StockState> find(StockKey key) {
        return Optional.ofNullable(states.get(key));
    }

    @Override
    public void save(StockKey key, StockState state) {
        states.put(key, state);
    }

    @Override
    public <T> T withKeyLock(StockKey key, Supplier<T> action) {
        ReentrantLock lock = locks[Math.floorMod(key.hashCode(), LOCK_STRIPES)];
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
