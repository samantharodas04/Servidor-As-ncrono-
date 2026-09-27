package image;

import view.PlannedChunk;

import java.util.Objects;

/** Une la geometria de un chunk con su imagen codificada. */
public record RenderedChunk(PlannedChunk chunk, byte[] bytes, boolean png) {
    public RenderedChunk {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0) {
            throw new IllegalArgumentException("El chunk no puede estar vacio");
        }
    }

    public int byteLength() {
        return bytes.length;
    }
}
