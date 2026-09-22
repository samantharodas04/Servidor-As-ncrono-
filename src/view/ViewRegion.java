package view;

/** Rectangulo expresado en coordenadas de la imagen original. */
public record ViewRegion(int x, int y, int width, int height) {
    public ViewRegion {
        if (x < 0 || y < 0) {
            throw new IllegalArgumentException("La posicion de la region no puede ser negativa");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("La region debe tener dimensiones positivas");
        }
    }
}
