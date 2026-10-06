package view;

import java.util.Objects;

/** Geometria estable del nivel y el area temporal necesaria para sus tiles. */
public record StableChunkPlan(
        int levelWidth,
        int levelHeight,
        int visibleLevelX,
        int visibleLevelY,
        int visibleWidth,
        int visibleHeight,
        double scaleX,
        double scaleY,
        double sourceOriginX,
        double sourceOriginY,
        ViewRegion visibleRegion,
        ViewRegion preparedRegion,
        ViewChunkPlan chunkPlan
) {
    public StableChunkPlan {
        if (levelWidth <= 0 || levelHeight <= 0
                || visibleWidth <= 0 || visibleHeight <= 0
                || scaleX <= 0.0 || scaleY <= 0.0
                || sourceOriginX < 0.0 || sourceOriginY < 0.0) {
            throw new IllegalArgumentException("Geometria estable invalida");
        }
        Objects.requireNonNull(visibleRegion, "visibleRegion");
        Objects.requireNonNull(preparedRegion, "preparedRegion");
        Objects.requireNonNull(chunkPlan, "chunkPlan");
    }
}
