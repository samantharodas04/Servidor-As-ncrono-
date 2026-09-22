package image;

import view.PlannedChunk;

import java.io.IOException;
import java.nio.file.Path;

/** Convierte una instruccion geometrica en bytes JPEG. */
public interface ChunkRenderer {
    byte[] render(Path source, PlannedChunk chunk) throws IOException;
}
