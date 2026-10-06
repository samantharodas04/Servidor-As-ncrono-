package image;

import view.PlannedChunk;

import java.io.IOException;
import java.nio.file.Path;

/** Convierte una instruccion geometrica en JPEG o PNG. */
public interface ChunkRenderer {
    byte[] render(Path source, PlannedChunk chunk, int jpegQuality, boolean png)
            throws IOException;
}
