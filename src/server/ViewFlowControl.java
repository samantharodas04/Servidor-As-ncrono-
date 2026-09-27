package server;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Ventana por sesión: cada chunk ocupa un cupo hasta recibir su ACK. */
final class ViewFlowControl {
    static final int WINDOW_CHUNKS = 4;
    private static final long ACK_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final Set<Integer> outstanding = new HashSet<>();
    private long generationId;
    private long lastAckNanos;
    private boolean closed;

    synchronized void begin(long nextGenerationId) throws IOException {
        if (closed || nextGenerationId <= generationId) {
            throw new IOException("Generacion VIEW invalida para esta sesion");
        }
        generationId = nextGenerationId;
        outstanding.clear();
        lastAckNanos = System.nanoTime();
        notifyAll();
    }

    synchronized boolean reserve(long currentGenerationId, int index) throws IOException {
        while (!closed && generationId == currentGenerationId
                && outstanding.size() >= WINDOW_CHUNKS) {
            awaitAck();
        }
        if (closed || generationId != currentGenerationId) return false;
        if (outstanding.isEmpty()) lastAckNanos = System.nanoTime();
        if (!outstanding.add(index)) {
            throw new IOException("Indice de chunk repetido en la ventana");
        }
        return true;
    }

    synchronized void acknowledge(long currentGenerationId, int index) throws IOException {
        if (closed || currentGenerationId != generationId) return;
        if (!outstanding.remove(index)) {
            throw new IOException("CHUNK_ACK duplicado o sin chunk enviado");
        }
        lastAckNanos = System.nanoTime();
        notifyAll();
    }

    synchronized boolean awaitDrained(long currentGenerationId) throws IOException {
        while (!closed && generationId == currentGenerationId && !outstanding.isEmpty()) {
            awaitAck();
        }
        return !closed && generationId == currentGenerationId;
    }

    private void awaitAck() throws IOException {
        long remaining = ACK_TIMEOUT_NANOS - (System.nanoTime() - lastAckNanos);
        if (remaining <= 0) throw new IOException("Tiempo de espera de CHUNK_ACK agotado");
        try {
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Vista cancelada mientras esperaba CHUNK_ACK", failure);
        }
    }

    synchronized void close() {
        closed = true;
        outstanding.clear();
        notifyAll();
    }
}
