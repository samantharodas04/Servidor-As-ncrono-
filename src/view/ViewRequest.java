package view;

import java.util.Objects;

/** Datos necesarios para solicitar una vista concreta de una imagen. */
public record ViewRequest(
        long generationId,
        String imageId,
        int zoomIndex,
        int centerX,
        int centerY,
        int viewportWidth,
        int viewportHeight,
        int chunkSize
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
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " debe ser positivo");
        }
    }
}
