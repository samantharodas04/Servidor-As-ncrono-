package cache;

/** Identidad inmutable de un chunk dentro de una version concreta de la imagen. */
public record ChunkCacheKey(
        String imageId,
        long sourceSizeBytes,
        long sourceModifiedMillis,
        int zoomIndex,
        int levelWidth,
        int levelHeight,
        int chunkSize,
        int column,
        int row
) {
    public ChunkCacheKey {
        if (imageId == null || imageId.isBlank()) {
            throw new IllegalArgumentException("El ID de imagen es obligatorio");
        }
        if (sourceSizeBytes < 0 || sourceModifiedMillis < 0
                || zoomIndex < 0 || levelWidth <= 0 || levelHeight <= 0
                || chunkSize <= 0 || column < 0 || row < 0) {
            throw new IllegalArgumentException("Clave de cache de chunk invalida");
        }
    }
}
