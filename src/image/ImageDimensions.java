package image;

/** Dimensiones originales obtenidas sin decodificar el raster completo. */
public record ImageDimensions(int width, int height) {
    public ImageDimensions {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Las dimensiones deben ser positivas");
        }
    }
}
