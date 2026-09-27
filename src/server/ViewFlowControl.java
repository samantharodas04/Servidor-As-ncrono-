package server;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Ventana por sesión: conserva solo los chunks enviados que aún esperan ACK. */
final class ViewFlowControl {
    static final int WINDOW_CHUNKS = 4;
    private static final long RETRY_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final int MAX_RETRIES = 3;

    private final ChunkSender sender;
    private final long retryNanos;
    private final int maxRetries;
    private final Map<Integer, PendingChunk> outstanding = new HashMap<>();
    private final Set<Integer> acknowledged = new HashSet<>();
    private long generationId;
    private boolean failed;
    private boolean closed;

    ViewFlowControl(ChunkSender sender) {
        this(sender, RETRY_NANOS, MAX_RETRIES);
    }

    ViewFlowControl(ChunkSender sender, long retryNanos, int maxRetries) {
        if (sender == null || retryNanos <= 0 || maxRetries < 0) {
            throw new IllegalArgumentException("Configuracion de reenvio invalida");
        }
        this.sender = sender;
        this.retryNanos = retryNanos;
        this.maxRetries = maxRetries;
    }

    synchronized void begin(long nextGenerationId) throws IOException {
        if (closed || nextGenerationId <= generationId) {
            throw new IOException("Generacion VIEW invalida para esta sesion");
        }
        generationId = nextGenerationId;
        failed = false;
        outstanding.clear();
        acknowledged.clear();
        notifyAll();
    }

    synchronized boolean sendChunk(long currentGenerationId, int index, byte[] payload)
            throws IOException {
        while (!closed && !failed && generationId == currentGenerationId
                && outstanding.size() >= WINDOW_CHUNKS) {
            awaitOrRetry(currentGenerationId);
        }
        if (closed || failed || generationId != currentGenerationId) return false;
        if (outstanding.containsKey(index) || acknowledged.contains(index)) {
            throw new IOException("Indice de chunk repetido en la vista");
        }
        PendingChunk pending = new PendingChunk(payload);
        outstanding.put(index, pending);
        try {
            sender.send(payload);
            pending.lastSentNanos = System.nanoTime();
        } catch (IOException failure) {
            outstanding.remove(index);
            throw failure;
        }
        return true;
    }

    synchronized void acknowledge(long currentGenerationId, int index) throws IOException {
        if (closed || failed || currentGenerationId != generationId) return;
        PendingChunk removed = outstanding.remove(index);
        if (removed == null) {
            if (acknowledged.contains(index)) return; // ACK tardío de un reenvío.
            throw new IOException("CHUNK_ACK sin chunk enviado");
        }
        acknowledged.add(index);
        notifyAll();
    }

    synchronized boolean awaitDrained(long currentGenerationId) throws IOException {
        while (!closed && !failed && generationId == currentGenerationId
                && !outstanding.isEmpty()) {
            awaitOrRetry(currentGenerationId);
        }
        return !closed && !failed && generationId == currentGenerationId;
    }

    private void awaitOrRetry(long currentGenerationId) throws IOException {
        long now = System.nanoTime();
        long earliest = Long.MAX_VALUE;
        for (PendingChunk pending : outstanding.values()) {
            earliest = Math.min(earliest, pending.lastSentNanos + retryNanos);
        }
        if (earliest == Long.MAX_VALUE) return;
        long remaining = earliest - now;
        if (remaining > 0) {
            try {
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException("Vista cancelada mientras esperaba CHUNK_ACK", failure);
            }
            return;
        }

        for (Map.Entry<Integer, PendingChunk> entry : outstanding.entrySet()) {
            PendingChunk pending = entry.getValue();
            if (now - pending.lastSentNanos < retryNanos) continue;
            if (pending.retries >= maxRetries) {
                throw new IOException("Sin CHUNK_ACK tras " + maxRetries
                        + " reenvios del chunk " + entry.getKey());
            }
            sender.send(pending.payload);
            pending.retries++;
            pending.lastSentNanos = System.nanoTime();
            System.out.printf("[PAI] CHUNK reenviado | generationId=%d | index=%d | intento=%d%n",
                    currentGenerationId, entry.getKey(), pending.retries);
        }
    }

    synchronized void close() {
        closed = true;
        outstanding.clear();
        acknowledged.clear();
        notifyAll();
    }

    synchronized void fail(long currentGenerationId) {
        if (generationId != currentGenerationId) return;
        failed = true;
        outstanding.clear();
        acknowledged.clear();
        notifyAll();
    }

    @FunctionalInterface
    interface ChunkSender {
        void send(byte[] payload) throws IOException;
    }

    private static final class PendingChunk {
        private final byte[] payload;
        private long lastSentNanos;
        private int retries;

        private PendingChunk(byte[] payload) {
            this.payload = payload;
        }
    }
}
