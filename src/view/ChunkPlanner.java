package view;

import java.util.ArrayList;
import java.util.List;

/** Planifica tiles anclados a la cuadricula global de un nivel de zoom. */
public final class ChunkPlanner {
    private ChunkPlanner() {
    }

    public static StableChunkPlan plan(
            int imageWidth,
            int imageHeight,
            ZoomLevel zoomLevel,
            int centerX,
            int centerY,
            int viewportWidth,
            int viewportHeight,
            int chunkSize
    ) {
        requirePositive(imageWidth, "imageWidth");
        requirePositive(imageHeight, "imageHeight");
        requirePositive(viewportWidth, "viewportWidth");
        requirePositive(viewportHeight, "viewportHeight");
        requirePositive(chunkSize, "chunkSize");
        if (centerX < 0 || centerX >= imageWidth
                || centerY < 0 || centerY >= imageHeight) {
            throw new IllegalArgumentException("El centro solicitado esta fuera de la imagen");
        }

        int levelWidth = Math.max(1, (int) Math.round(imageWidth * zoomLevel.scale()));
        int levelHeight = Math.max(1, (int) Math.round(imageHeight * zoomLevel.scale()));
        double scaleX = (double) levelWidth / imageWidth;
        double scaleY = (double) levelHeight / imageHeight;

        int visibleWidth = Math.min(viewportWidth, levelWidth);
        int visibleHeight = Math.min(viewportHeight, levelHeight);
        int centerLevelX = proportionalPoint(centerX, imageWidth, levelWidth);
        int centerLevelY = proportionalPoint(centerY, imageHeight, levelHeight);
        int visibleLevelX = clamp(
                centerLevelX - visibleWidth / 2,
                0,
                levelWidth - visibleWidth
        );
        int visibleLevelY = clamp(
                centerLevelY - visibleHeight / 2,
                0,
                levelHeight - visibleHeight
        );

        int firstColumn = visibleLevelX / chunkSize;
        int lastColumn = (visibleLevelX + visibleWidth - 1) / chunkSize;
        int firstRow = visibleLevelY / chunkSize;
        int lastRow = (visibleLevelY + visibleHeight - 1) / chunkSize;

        int preparedLevelX = firstColumn * chunkSize;
        int preparedLevelY = firstRow * chunkSize;
        int preparedLevelEndX = Math.min(levelWidth, (lastColumn + 1) * chunkSize);
        int preparedLevelEndY = Math.min(levelHeight, (lastRow + 1) * chunkSize);
        int preparedWidth = preparedLevelEndX - preparedLevelX;
        int preparedHeight = preparedLevelEndY - preparedLevelY;

        int imageCanvasX = (viewportWidth - visibleWidth) / 2;
        int imageCanvasY = (viewportHeight - visibleHeight) / 2;
        int preparedCanvasX = imageCanvasX + preparedLevelX - visibleLevelX;
        int preparedCanvasY = imageCanvasY + preparedLevelY - visibleLevelY;

        List<PlannedChunk> chunks = new ArrayList<>();
        for (int row = firstRow; row <= lastRow; row++) {
            int tileY = row * chunkSize;
            int tileEndY = Math.min(levelHeight, tileY + chunkSize);
            int sourceY0 = proportionalBoundary(tileY, imageHeight, levelHeight);
            int sourceY1 = proportionalBoundary(tileEndY, imageHeight, levelHeight);

            for (int column = firstColumn; column <= lastColumn; column++) {
                int tileX = column * chunkSize;
                int tileEndX = Math.min(levelWidth, tileX + chunkSize);
                int sourceX0 = proportionalBoundary(tileX, imageWidth, levelWidth);
                int sourceX1 = proportionalBoundary(tileEndX, imageWidth, levelWidth);

                chunks.add(new PlannedChunk(
                        column,
                        row,
                        sourceX0,
                        sourceY0,
                        sourceX1 - sourceX0,
                        sourceY1 - sourceY0,
                        imageCanvasX + tileX - visibleLevelX,
                        imageCanvasY + tileY - visibleLevelY,
                        tileEndX - tileX,
                        tileEndY - tileY
                ));
            }
        }

        ViewRegion visibleRegion = sourceRegion(
                visibleLevelX,
                visibleLevelY,
                visibleLevelX + visibleWidth,
                visibleLevelY + visibleHeight,
                imageWidth,
                imageHeight,
                levelWidth,
                levelHeight
        );
        ViewRegion preparedRegion = sourceRegion(
                preparedLevelX,
                preparedLevelY,
                preparedLevelEndX,
                preparedLevelEndY,
                imageWidth,
                imageHeight,
                levelWidth,
                levelHeight
        );
        ViewChunkPlan chunkPlan = new ViewChunkPlan(
                preparedCanvasX,
                preparedCanvasY,
                preparedWidth,
                preparedHeight,
                chunks
        );

        return new StableChunkPlan(
                levelWidth,
                levelHeight,
                visibleLevelX,
                visibleLevelY,
                visibleWidth,
                visibleHeight,
                scaleX,
                scaleY,
                (double) preparedLevelX / scaleX,
                (double) preparedLevelY / scaleY,
                visibleRegion,
                preparedRegion,
                chunkPlan
        );
    }

    private static ViewRegion sourceRegion(
            int x0,
            int y0,
            int x1,
            int y1,
            int imageWidth,
            int imageHeight,
            int levelWidth,
            int levelHeight
    ) {
        int sourceX0 = proportionalBoundary(x0, imageWidth, levelWidth);
        int sourceY0 = proportionalBoundary(y0, imageHeight, levelHeight);
        int sourceX1 = proportionalBoundary(x1, imageWidth, levelWidth);
        int sourceY1 = proportionalBoundary(y1, imageHeight, levelHeight);
        return new ViewRegion(
                sourceX0,
                sourceY0,
                sourceX1 - sourceX0,
                sourceY1 - sourceY0
        );
    }

    private static int proportionalPoint(int point, int sourceSize, int outputSize) {
        return Math.min(outputSize - 1, (int) ((long) point * outputSize / sourceSize));
    }

    private static int proportionalBoundary(int boundary, int sourceSize, int outputSize) {
        return (int) ((long) boundary * sourceSize / outputSize);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " debe ser positivo");
        }
    }
}
