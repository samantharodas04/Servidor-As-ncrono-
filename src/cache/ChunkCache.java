package cache;

import java.util.LinkedHashMap;
import java.util.Map;

/** Cache GreedyDual-Size limitada por los bytes JPEG almacenados. */
public final class ChunkCache {
    private static final double BYTES_PER_MIB = 1024.0 * 1024.0;

    private final long maximumBytes;
    private final LinkedHashMap<ChunkCacheKey, Entry> entries = new LinkedHashMap<>();
    private long currentBytes;
    private double age;

    public ChunkCache(long maximumBytes) {
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException("El limite de cache debe ser positivo");
        }
        this.maximumBytes = maximumBytes;
    }

    public synchronized byte[] get(ChunkCacheKey key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        entry.priority = priority(entry.costMillis, entry.jpeg.length);
        return entry.jpeg.clone();
    }

    /** Publica un lote completo despues de que toda la generacion termino correctamente. */
    public synchronized void putAll(
            Map<ChunkCacheKey, byte[]> completedChunks, double costMillisPerChunk
    ) {
        if (!Double.isFinite(costMillisPerChunk) || costMillisPerChunk <= 0) {
            throw new IllegalArgumentException("El costo de generacion debe ser positivo");
        }
        for (Map.Entry<ChunkCacheKey, byte[]> entry : completedChunks.entrySet()) {
            put(entry.getKey(), entry.getValue(), costMillisPerChunk);
        }
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized long currentBytes() {
        return currentBytes;
    }

    public long maximumBytes() {
        return maximumBytes;
    }

    private void put(ChunkCacheKey key, byte[] jpeg, double costMillis) {
        if (key == null || jpeg == null || jpeg.length == 0) {
            throw new IllegalArgumentException("No se puede guardar un chunk vacio");
        }
        if (jpeg.length > maximumBytes) {
            return;
        }

        Entry previous = entries.remove(key);
        if (previous != null) {
            currentBytes -= previous.jpeg.length;
        }

        byte[] safeCopy = jpeg.clone();
        while (currentBytes > maximumBytes - safeCopy.length) {
            evictLowestPriority();
        }
        entries.put(key, new Entry(safeCopy, costMillis, priority(costMillis, safeCopy.length)));
        currentBytes += safeCopy.length;
    }

    private double priority(double costMillis, int bytes) {
        return age + costMillis / (bytes / BYTES_PER_MIB);
    }

    private void evictLowestPriority() {
        ChunkCacheKey victimKey = null;
        Entry victim = null;
        for (Map.Entry<ChunkCacheKey, Entry> candidate : entries.entrySet()) {
            if (victim == null || candidate.getValue().priority < victim.priority) {
                victimKey = candidate.getKey();
                victim = candidate.getValue();
            }
        }
        if (victim == null) {
            throw new IllegalStateException("La cache no tiene entradas para expulsar");
        }
        age = victim.priority;
        entries.remove(victimKey);
        currentBytes -= victim.jpeg.length;
    }

    private static final class Entry {
        private final byte[] jpeg;
        private final double costMillis;
        private double priority;

        private Entry(byte[] jpeg, double costMillis, double priority) {
            this.jpeg = jpeg;
            this.costMillis = costMillis;
            this.priority = priority;
        }
    }
}
