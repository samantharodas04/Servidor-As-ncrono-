package view;

/**
 * Una escala permitida y el tamano de la region original visible en ella.
 */
public record ZoomLevel(
        int index,
        double scale,
        int sourceViewWidth,
        int sourceViewHeight
) {
    public ZoomLevel {
        if (index < 0) {
            throw new IllegalArgumentException("El indice no puede ser negativo");
        }
        if (!(scale > 0.0 && scale <= 1.0)) {
            throw new IllegalArgumentException("La escala debe estar entre 0 y 1");
        }
        if (sourceViewWidth <= 0 || sourceViewHeight <= 0) {
            throw new IllegalArgumentException("La region visible debe tener dimensiones positivas");
        }
    }
}
