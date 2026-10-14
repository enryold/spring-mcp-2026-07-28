package io.github.enryold.mcp;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Host-owned bounded state for resumable interactions and asynchronous tasks. */
public final class McpInteractionStore {
    private final int maxContinuations;
    private final int maxTasks;
    private final Map<String, McpInteractions.Continuation> continuations = new LinkedHashMap<>();
    private final Map<String, McpInteractions.TaskRecord> tasks = new LinkedHashMap<>();

    public McpInteractionStore(int maxContinuations, int maxTasks) {
        if (maxContinuations < 1 || maxTasks < 1) throw new IllegalArgumentException("store limits must be positive");
        this.maxContinuations = maxContinuations;
        this.maxTasks = maxTasks;
    }

    void putContinuation(McpInteractions.Continuation continuation, Instant now) {
        List<McpInteractions.TaskRecord> expired;
        boolean full;
        synchronized (this) {
            expired = purgeLocked(now);
            full = continuations.size() >= maxContinuations;
            if (!full) continuations.put(continuation.token, continuation);
        }
        expireOutsideLock(expired);
        if (full) throw new IllegalStateException("continuation capacity reached");
    }

    McpInteractions.Continuation continuation(String token, Instant now) {
        Objects.requireNonNull(token, "token");
        List<McpInteractions.TaskRecord> expired;
        McpInteractions.Continuation result;
        synchronized (this) {
            expired = purgeLocked(now);
            result = continuations.get(token);
        }
        expireOutsideLock(expired);
        return result;
    }

    void putTask(McpInteractions.TaskRecord task, Instant now) {
        List<McpInteractions.TaskRecord> expired;
        boolean full;
        synchronized (this) {
            expired = purgeLocked(now);
            full = tasks.size() >= maxTasks;
            if (!full) tasks.put(task.taskId, task);
        }
        expireOutsideLock(expired);
        if (full) throw new IllegalStateException("task capacity reached");
    }

    McpInteractions.TaskRecord task(String taskId, Instant now) {
        Objects.requireNonNull(taskId, "taskId");
        List<McpInteractions.TaskRecord> expired;
        McpInteractions.TaskRecord result;
        synchronized (this) {
            expired = purgeLocked(now);
            result = tasks.get(taskId);
            if (result != null && result.expired) result = null;
        }
        expireOutsideLock(expired);
        return result;
    }

    synchronized void removeTask(String taskId, McpInteractions.TaskRecord expected) {
        tasks.remove(taskId, expected);
    }

    synchronized void executionExited(McpInteractions.TaskRecord task) {
        if (task.expired && task.liveExecutions.get() == 0) tasks.remove(task.taskId, task);
    }

    public synchronized int continuationCount() {
        return continuations.size();
    }

    public synchronized int taskCount() {
        return tasks.size();
    }

    private List<McpInteractions.TaskRecord> purgeLocked(Instant now) {
        continuations.values().removeIf(value -> !now.isBefore(value.expiresAt));
        List<McpInteractions.TaskRecord> expired = new java.util.ArrayList<>();
        var iterator = tasks.values().iterator();
        while (iterator.hasNext()) {
            McpInteractions.TaskRecord task = iterator.next();
            if (now.isBefore(task.expiresAt)) continue;
            if (!task.expired) {
                task.expired = true;
                expired.add(task);
            }
            if (task.liveExecutions.get() == 0) iterator.remove();
        }
        return expired;
    }

    private static void expireOutsideLock(List<McpInteractions.TaskRecord> expired) {
        expired.forEach(McpInteractions.TaskRecord::expireExecution);
    }
}
