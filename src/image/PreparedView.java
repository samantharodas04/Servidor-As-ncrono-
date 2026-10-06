package image;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Vista pequena y temporal desde la cual se recortan los chunks visibles. */
public record PreparedView(Path path, int width, int height) implements AutoCloseable {
    public PreparedView {
        Objects.requireNonNull(path, "path");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("La vista preparada debe tener dimensiones positivas");
        }
    }

    @Override
    public void close() throws IOException {
        Files.deleteIfExists(path);
    }
}
