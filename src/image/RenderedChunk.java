package image;

import view.PlannedChunk;

import java.util.Objects;

/** Une la geometria de un chunk con sus bytes JPEG ya generados. */
public record RenderedChunk(PlannedChunk chunk, byte[] jpeg) {
    public RenderedChunk {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(jpeg, "jpeg");
        if (jpeg.length == 0) {
            throw new IllegalArgumentException("El JPEG no puede estar vacio");
        }
    }

    public int byteLength() {
        return jpeg.length;
    }
}
