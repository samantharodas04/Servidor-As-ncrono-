package view;

/** Relaciona un tile global con el original y su posicion visible en el canvas. */
public record PlannedChunk(
        int column,
        int row,
        int sourceX,
        int sourceY,
        int sourceWidth,
        int sourceHeight,
        int canvasX,
        int canvasY,
        int outputWidth,
        int outputHeight
) {
    public PlannedChunk {
        if (column < 0 || row < 0 || sourceX < 0 || sourceY < 0) {
            throw new IllegalArgumentException("Las coordenadas globales no pueden ser negativas");
        }
        if (sourceWidth <= 0 || sourceHeight <= 0
                || outputWidth <= 0 || outputHeight <= 0) {
            throw new IllegalArgumentException("Las dimensiones deben ser positivas");
        }
    }
}
