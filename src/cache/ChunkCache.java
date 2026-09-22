package cache;

import java.util.LinkedHashMap;
import java.util.Map;

/** Cache LRU en memoria limitada por la suma de bytes JPEG. */
public final class ChunkCache {
    private final long maximumBytes;
    private final LinkedHashMap<ChunkCacheKey, byte[]> entries;
    private long currentBytes;

    public ChunkCache(long maximumBytes) {
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException("El limite de cache debe ser positivo");
        }
        this.maximumBytes = maximumBytes;
        this.entries = new LinkedHashMap<>(16, 0.75f, true);
    }

    public synchronized byte[] get(ChunkCacheKey key) {
        byte[] jpeg = entries.get(key);
        return jpeg == null ? null : jpeg.clone();
    }

    /** Publica un lote completo despues de que toda la generacion termino correctamente. */
    public synchronized void putAll(Map<ChunkCacheKey, byte[]> completedChunks) {
        for (Map.Entry<ChunkCacheKey, byte[]> entry : completedChunks.entrySet()) {
            put(entry.getKey(), entry.getValue());
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

    private void put(ChunkCacheKey key, byte[] jpeg) {
        if (key == null || jpeg == null || jpeg.length == 0) {
            throw new IllegalArgumentException("No se puede guardar un chunk vacio");
        }
        if (jpeg.length > maximumBytes) {
            return;
        }

        byte[] safeCopy = jpeg.clone();
        byte[] previous = entries.put(key, safeCopy);
        if (previous != null) {
            currentBytes -= previous.length;
        }
        currentBytes += safeCopy.length;
        evictLeastRecentlyUsed();
    }

    private void evictLeastRecentlyUsed() {
        var iterator = entries.entrySet().iterator();
        while (currentBytes > maximumBytes && iterator.hasNext()) {
            Map.Entry<ChunkCacheKey, byte[]> eldest = iterator.next();
            currentBytes -= eldest.getValue().length;
            iterator.remove();
        }
    }
}
