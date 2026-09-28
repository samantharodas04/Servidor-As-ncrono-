package view;

/** Chunk que el cliente afirma conservar decodificado para la siguiente VIEW. */
public record CachedChunk(int column, int row, int width, int height) {
    public CachedChunk {
        if (column < 0 || row < 0 || width < 1 || height < 1) {
            throw new IllegalArgumentException("Referencia de chunk invalida");
        }
    }

    public boolean matches(PlannedChunk chunk) {
        return column == chunk.column() && row == chunk.row()
                && width == chunk.outputWidth() && height == chunk.outputHeight();
    }
}
