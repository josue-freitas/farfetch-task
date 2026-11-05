package com.example.availability.repository;

import com.example.availability.model.StockKey;
import com.example.availability.model.StockState;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Stores the latest successfully applied version of every stock key. It is the single source of
 * truth for stale/duplicate detection across BOTH input paths (events and CSV rows).
 */
public interface StockStateRepository {

    Optional<StockState> find(StockKey key);

    void save(StockKey key, StockState state);

    /**
     * Runs {@code action} while holding an exclusive lock for {@code key}. Updates for the same key
     * are therefore serialized (check-version -> write -> record-version is atomic), while different
     * keys are processed in parallel.
     */
    <T> T withKeyLock(StockKey key, Supplier<T> action);
}
