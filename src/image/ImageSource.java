package image;

import java.nio.file.Path;
import java.util.Objects;

/** Describe un archivo original; no una version procesada ni un tile. */
public record ImageSource(
        String id,
        String fileName,
        Path path,
        long sizeBytes,
        long modifiedMillis,
        int width,
        int height
) {
    public ImageSource {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(path, "path");
        if (id.isBlank()) {
            throw new IllegalArgumentException("El identificador de imagen no puede estar vacio");
        }
        if (sizeBytes < 0 || modifiedMillis < 0) {
            throw new IllegalArgumentException("La version del archivo no puede ser negativa");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Las dimensiones deben ser positivas");
        }
        path = path.toAbsolutePath().normalize();
    }
}
