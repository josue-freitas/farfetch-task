package com.example.availability.repository;

import com.example.availability.model.ImportJob;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory job registry (jobs are lost on restart; production would persist them).
 */
@ApplicationScoped
public class ImportJobRepository {

    private final Map<String, ImportJob> jobs = new ConcurrentHashMap<>();

    public void save(ImportJob job) {
        jobs.put(job.id(), job);
    }

    public Optional<ImportJob> find(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }
}
