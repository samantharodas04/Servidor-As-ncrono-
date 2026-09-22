package view;

import image.ImageSource;
import image.RenderedChunk;

import java.util.List;
import java.util.Objects;

/** Resultado completo de procesar una solicitud de vista. */
public record ViewResult(
        ViewRequest request,
        ImageSource source,
        ZoomLevel zoomLevel,
        ViewRegion region,
        ViewRegion preparedRegion,
        ViewChunkPlan plan,
        List<RenderedChunk> chunks,
        int cacheHits,
        int generatedChunks,
        long preparationMillis,
        long chunkMillis,
        long totalMillis
) {
    public ViewResult {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(zoomLevel, "zoomLevel");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(preparedRegion, "preparedRegion");
        Objects.requireNonNull(plan, "plan");
        chunks = List.copyOf(chunks);
        if (chunks.size() != plan.chunks().size()) {
            throw new IllegalArgumentException("Faltan chunks en el resultado de la vista");
        }
        if (cacheHits < 0 || generatedChunks < 0
                || cacheHits + generatedChunks != chunks.size()) {
            throw new IllegalArgumentException("Conteo de cache y generacion inconsistente");
        }
        if (preparationMillis < 0 || chunkMillis < 0 || totalMillis < 0) {
            throw new IllegalArgumentException("Los tiempos no pueden ser negativos");
        }
    }

    public long totalJpegBytes() {
        return chunks.stream().mapToLong(RenderedChunk::byteLength).sum();
    }
}
