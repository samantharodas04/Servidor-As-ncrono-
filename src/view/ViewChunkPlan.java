package view;

import java.util.List;

/** Area temporal alineada a tiles y chunks que la componen. */
public record ViewChunkPlan(
        int canvasX,
        int canvasY,
        int renderedWidth,
        int renderedHeight,
        List<PlannedChunk> chunks
) {
    public ViewChunkPlan {
        if (renderedWidth <= 0 || renderedHeight <= 0) {
            throw new IllegalArgumentException("La vista renderizada debe tener dimensiones positivas");
        }
        chunks = List.copyOf(chunks);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("El plan debe contener al menos un chunk");
        }
    }
}
