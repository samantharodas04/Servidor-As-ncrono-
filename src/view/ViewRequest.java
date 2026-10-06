package view;

import java.util.Objects;
import java.util.List;

/** Datos necesarios para solicitar una vista concreta de una imagen. */
public record ViewRequest(
        long generationId,
        String imageId,
        int zoomIndex,
        int centerX,
        int centerY,
        int viewportWidth,
        int viewportHeight,
        int chunkSize,
        List<CachedChunk> cachedChunks
) {
    public ViewRequest {
        if (generationId < 1) {
            throw new IllegalArgumentException("La generacion debe ser positiva");
        }
        Objects.requireNonNull(imageId, "imageId");
        if (imageId.isBlank()) {
            throw new IllegalArgumentException("El identificador de imagen no puede estar vacio");
        }
        if (zoomIndex < 0) {
            throw new IllegalArgumentException("El zoom no puede ser negativo");
        }
        if (centerX < 0 || centerY < 0) {
            throw new IllegalArgumentException("El centro de la vista no puede ser negativo");
        }
        requirePositive(viewportWidth, "viewportWidth");
        requirePositive(viewportHeight, "viewportHeight");
        requirePositive(chunkSize, "chunkSize");
        cachedChunks = List.copyOf(cachedChunks);
        if (cachedChunks.size() > 128 || cachedChunks.stream().distinct().count() != cachedChunks.size()) {
            throw new IllegalArgumentException("Lista de chunks reutilizables invalida");
        }
    }

    public ViewRequest(long generationId, String imageId, int zoomIndex,
                       int centerX, int centerY, int viewportWidth,
                       int viewportHeight, int chunkSize) {
        this(generationId, imageId, zoomIndex, centerX, centerY,
                viewportWidth, viewportHeight, chunkSize, List.of());
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " debe ser positivo");
        }
    }
}
