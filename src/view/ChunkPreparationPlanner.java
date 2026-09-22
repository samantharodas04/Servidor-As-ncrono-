package view;

import java.util.List;
import java.util.Objects;

/** Reduce el area temporal al rectangulo que contiene los chunks faltantes. */
public final class ChunkPreparationPlanner {
    private ChunkPreparationPlanner() {
    }

    public static StableChunkPlan forMissingChunks(
            StableChunkPlan completePlan,
            List<PlannedChunk> missingChunks
    ) {
        Objects.requireNonNull(completePlan, "completePlan");
        List<PlannedChunk> chunks = List.copyOf(missingChunks);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("Debe existir al menos un chunk faltante");
        }

        int canvasX = chunks.stream().mapToInt(PlannedChunk::canvasX).min().orElseThrow();
        int canvasY = chunks.stream().mapToInt(PlannedChunk::canvasY).min().orElseThrow();
        int canvasEndX = chunks.stream()
                .mapToInt(chunk -> chunk.canvasX() + chunk.outputWidth())
                .max().orElseThrow();
        int canvasEndY = chunks.stream()
                .mapToInt(chunk -> chunk.canvasY() + chunk.outputHeight())
                .max().orElseThrow();

        int sourceX = chunks.stream().mapToInt(PlannedChunk::sourceX).min().orElseThrow();
        int sourceY = chunks.stream().mapToInt(PlannedChunk::sourceY).min().orElseThrow();
        int sourceEndX = chunks.stream()
                .mapToInt(chunk -> chunk.sourceX() + chunk.sourceWidth())
                .max().orElseThrow();
        int sourceEndY = chunks.stream()
                .mapToInt(chunk -> chunk.sourceY() + chunk.sourceHeight())
                .max().orElseThrow();

        ViewChunkPlan completeChunkPlan = completePlan.chunkPlan();
        double sourceOriginX = completePlan.sourceOriginX()
                + (canvasX - completeChunkPlan.canvasX()) / completePlan.scaleX();
        double sourceOriginY = completePlan.sourceOriginY()
                + (canvasY - completeChunkPlan.canvasY()) / completePlan.scaleY();
        ViewChunkPlan missingPlan = new ViewChunkPlan(
                canvasX,
                canvasY,
                canvasEndX - canvasX,
                canvasEndY - canvasY,
                chunks
        );

        return new StableChunkPlan(
                completePlan.levelWidth(),
                completePlan.levelHeight(),
                completePlan.visibleLevelX(),
                completePlan.visibleLevelY(),
                completePlan.visibleWidth(),
                completePlan.visibleHeight(),
                completePlan.scaleX(),
                completePlan.scaleY(),
                sourceOriginX,
                sourceOriginY,
                completePlan.visibleRegion(),
                new ViewRegion(
                        sourceX,
                        sourceY,
                        sourceEndX - sourceX,
                        sourceEndY - sourceY
                ),
                missingPlan
        );
    }
}
