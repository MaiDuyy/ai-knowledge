package com.security.security.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Debounced batch wiki generation per workspace, inspired by WeKnora's wiki_ingest pattern.
 *
 * When multiple documents are ingested rapidly, this service:
 *   1. Collects document IDs per workspace in a queue
 *   2. Waits 30 seconds (debounce) after the last enqueue
 *   3. Acquires a Redis lock to prevent concurrent compilation
 *   4. Triggers MRP pipeline for up to MAX_BATCH_SIZE documents at once
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DebouncedWikiTrigger {

    private final StringRedisTemplate redisTemplate;
    private final MrpPipelineService mrpPipelineService;

    private static final int DEBOUNCE_SECONDS = 30;
    private static final int MAX_BATCH_SIZE = 5;
    private static final Duration LOCK_TTL = Duration.ofMinutes(10);
    private static final String LOCK_PREFIX = "wiki:compile:lock:";

    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<PendingDoc>> pendingByWorkspace = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastEnqueueTime = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = Thread.ofVirtual().unstarted(r);
        t.setName("wiki-debounce-scheduler");
        return t;
    });

    public void enqueue(Long documentId, String workspaceId, String userId) {
        String wsKey = workspaceId != null ? workspaceId : "default";
        log.info("[WikiTrigger] Enqueuing doc={} for workspace={}", documentId, wsKey);

        pendingByWorkspace
                .computeIfAbsent(wsKey, k -> new ConcurrentLinkedQueue<>())
                .add(new PendingDoc(documentId, userId));

        lastEnqueueTime.put(wsKey, System.currentTimeMillis());

        scheduler.schedule(() -> tryFlush(wsKey), DEBOUNCE_SECONDS, TimeUnit.SECONDS);
    }

    private void tryFlush(String workspaceId) {
        Long lastTime = lastEnqueueTime.get(workspaceId);
        if (lastTime == null) return;

        long elapsed = System.currentTimeMillis() - lastTime;
        if (elapsed < (DEBOUNCE_SECONDS * 1000L - 500)) {
            log.debug("[WikiTrigger] Debounce not elapsed for workspace={}, skipping", workspaceId);
            return;
        }

        ConcurrentLinkedQueue<PendingDoc> queue = pendingByWorkspace.get(workspaceId);
        if (queue == null || queue.isEmpty()) return;

        String lockKey = LOCK_PREFIX + workspaceId;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "locked", LOCK_TTL);

        if (!Boolean.TRUE.equals(acquired)) {
            log.info("[WikiTrigger] Lock held for workspace={}, rescheduling", workspaceId);
            scheduler.schedule(() -> tryFlush(workspaceId), DEBOUNCE_SECONDS, TimeUnit.SECONDS);
            return;
        }

        try {
            List<PendingDoc> batch = new ArrayList<>();
            PendingDoc doc;
            while ((doc = queue.poll()) != null && batch.size() < MAX_BATCH_SIZE) {
                batch.add(doc);
            }

            if (batch.isEmpty()) return;

            log.info("[WikiTrigger] Flushing batch of {} docs for workspace={}", batch.size(), workspaceId);

            for (PendingDoc pd : batch) {
                try {
                    mrpPipelineService.initiateCompile(pd.documentId, workspaceId, pd.userId, true);
                } catch (Exception e) {
                    log.error("[WikiTrigger] Wiki compile failed for doc={}: {}", pd.documentId, e.getMessage());
                }
            }

            if (!queue.isEmpty()) {
                lastEnqueueTime.put(workspaceId, System.currentTimeMillis());
                scheduler.schedule(() -> tryFlush(workspaceId), DEBOUNCE_SECONDS, TimeUnit.SECONDS);
            }
        } finally {
            try {
                redisTemplate.delete(lockKey);
            } catch (Exception e) {
                log.warn("[WikiTrigger] Failed to release lock for workspace={}: {}", workspaceId, e.getMessage());
            }
        }
    }

    private record PendingDoc(Long documentId, String userId) {}
}
