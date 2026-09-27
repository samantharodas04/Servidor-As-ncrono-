package view;

import java.util.ArrayList;
import java.util.List;

/** Calcula niveles de zoom; no crea imagenes, capas ni archivos. */
public final class ZoomCalculator {
    private static final double NATIVE_SCALE = 1.0;
    private static final double GIANT_MAX_SCALE = 4.0;
    private static final int GIANT_MIN_EDGE = 50_000;
    private static final double EPSILON = 1.0e-12;
    private static final int MAX_LEVELS = 64;

    private ZoomCalculator() {
    }

    public static List<ZoomLevel> calculate(
            int imageWidth,
            int imageHeight,
            int viewportWidth,
            int viewportHeight
    ) {
        requirePositive(imageWidth, "imageWidth");
        requirePositive(imageHeight, "imageHeight");
        requirePositive(viewportWidth, "viewportWidth");
        requirePositive(viewportHeight, "viewportHeight");

        double fitWidth = (double) viewportWidth / imageWidth;
        double fitHeight = (double) viewportHeight / imageHeight;
        double baseScale = Math.min(NATIVE_SCALE, Math.min(fitWidth, fitHeight));
        double maximumScale = Math.max(imageWidth, imageHeight) >= GIANT_MIN_EDGE
                ? GIANT_MAX_SCALE : NATIVE_SCALE;

        List<ZoomLevel> levels = new ArrayList<>();
        double scale = baseScale;

        for (int index = 0; index < MAX_LEVELS; index++) {
            levels.add(createLevel(index, scale, imageWidth, imageHeight,
                    viewportWidth, viewportHeight));

            if (scale >= maximumScale - EPSILON) {
                return List.copyOf(levels);
            }

            scale = scale < NATIVE_SCALE - EPSILON
                    ? Math.min(NATIVE_SCALE, scale * 2.0)
                    : Math.min(maximumScale, scale * 2.0);
        }

        throw new IllegalStateException("Se excedio el maximo de niveles de zoom");
    }

    private static ZoomLevel createLevel(
            int index,
            double scale,
            int imageWidth,
            int imageHeight,
            int viewportWidth,
            int viewportHeight
    ) {
        int sourceViewWidth = (int) Math.min(
                imageWidth,
                Math.ceil(viewportWidth / scale)
        );
        int sourceViewHeight = (int) Math.min(
                imageHeight,
                Math.ceil(viewportHeight / scale)
        );

        return new ZoomLevel(index, scale, sourceViewWidth, sourceViewHeight);
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " debe ser positivo");
        }
    }
}
