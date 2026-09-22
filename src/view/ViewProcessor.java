package view;

import cache.ChunkCache;
import cache.ChunkCacheKey;
import image.ImageSource;
import image.PreparedView;
import image.RenderedChunk;
import image.VipsChunkRenderer;
import image.VipsViewPreparer;
import worker.ChunkWorkerPool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Convierte una ViewRequest en chunks JPEG estables sin ocuparse de la interfaz. */
public final class ViewProcessor implements AutoCloseable {
    private static final long DEFAULT_CACHE_BYTES = 32L * 1024L * 1024L;

    private final Map<String, ImageSource> sourcesById;
    private final VipsViewPreparer viewPreparer;
    private final ChunkWorkerPool workers;
    private final ChunkCache cache;

    public ViewProcessor(List<ImageSource> sources, int workerCount) {
        this(sources, workerCount, DEFAULT_CACHE_BYTES);
    }

    public ViewProcessor(List<ImageSource> sources, int workerCount, long maximumCacheBytes) {
        this.sourcesById = indexSources(sources);
        this.viewPreparer = new VipsViewPreparer();
        this.workers = new ChunkWorkerPool(workerCount, new VipsChunkRenderer());
        this.cache = new ChunkCache(maximumCacheBytes);
    }

    public ViewResult render(ViewRequest request) throws IOException {
        long startedAt = System.nanoTime();
        ImageSource source = findSource(request.imageId());
        ZoomLevel zoomLevel = findZoomLevel(source, request);
        StableChunkPlan stablePlan = ChunkPlanner.plan(
                source.width(),
                source.height(),
                zoomLevel,
                request.centerX(),
                request.centerY(),
                request.viewportWidth(),
                request.viewportHeight(),
                request.chunkSize()
        );
        ViewChunkPlan completePlan = stablePlan.chunkPlan();

        Map<PlannedChunk, RenderedChunk> available = new LinkedHashMap<>();
        List<PlannedChunk> missing = new ArrayList<>();
        for (PlannedChunk chunk : completePlan.chunks()) {
            byte[] cachedJpeg = cache.get(cacheKey(source, request, stablePlan, chunk));
            if (cachedJpeg == null) {
                missing.add(chunk);
            } else {
                available.put(chunk, new RenderedChunk(chunk, cachedJpeg));
            }
        }

        int cacheHits = available.size();
        long preparationMillis = 0;
        long chunkMillis = 0;
        if (!missing.isEmpty()) {
            ensureNotCancelled();
            StableChunkPlan generationPlan = ChunkPreparationPlanner.forMissingChunks(
                    stablePlan, missing
            );

            long preparationStartedAt = System.nanoTime();
            List<RenderedChunk> generated;
            try (PreparedView preparedView = viewPreparer.prepare(
                    source.path(), generationPlan
            )) {
                preparationMillis = elapsedMillis(preparationStartedAt);
                ensureNotCancelled();
                long chunksStartedAt = System.nanoTime();
                generated = workers.renderAll(
                        preparedView.path(), generationPlan.chunkPlan()
                );
                chunkMillis = elapsedMillis(chunksStartedAt);
            }

            ensureNotCancelled();
            validateGeneratedChunks(missing, generated);

            Map<ChunkCacheKey, byte[]> completedBatch = new LinkedHashMap<>();
            for (RenderedChunk rendered : generated) {
                available.put(rendered.chunk(), rendered);
                completedBatch.put(
                        cacheKey(source, request, stablePlan, rendered.chunk()),
                        rendered.jpeg()
                );
            }
            cache.putAll(completedBatch);
        }

        List<RenderedChunk> orderedChunks = completePlan.chunks().stream()
                .map(available::get)
                .toList();
        validateCoverage(stablePlan.preparedRegion(), completePlan, orderedChunks);
        return new ViewResult(
                request,
                source,
                zoomLevel,
                stablePlan.visibleRegion(),
                stablePlan.preparedRegion(),
                completePlan,
                orderedChunks,
                cacheHits,
                missing.size(),
                preparationMillis,
                chunkMillis,
                elapsedMillis(startedAt)
        );
    }

    public int cachedChunkCount() {
        return cache.size();
    }

    public long cachedBytes() {
        return cache.currentBytes();
    }

    public long maximumCacheBytes() {
        return cache.maximumBytes();
    }

    private ChunkCacheKey cacheKey(
            ImageSource source,
            ViewRequest request,
            StableChunkPlan plan,
            PlannedChunk chunk
    ) {
        return new ChunkCacheKey(
                source.id(),
                source.sizeBytes(),
                source.modifiedMillis(),
                request.zoomIndex(),
                plan.levelWidth(),
                plan.levelHeight(),
                request.chunkSize(),
                chunk.column(),
                chunk.row()
        );
    }

    private Map<String, ImageSource> indexSources(List<ImageSource> sources) {
        Map<String, ImageSource> indexed = new LinkedHashMap<>();
        for (ImageSource source : List.copyOf(sources)) {
            ImageSource previous = indexed.putIfAbsent(source.id(), source);
            if (previous != null) {
                throw new IllegalArgumentException("ID de imagen duplicado: " + source.id());
            }
        }
        return Map.copyOf(indexed);
    }

    private ImageSource findSource(String imageId) {
        ImageSource source = sourcesById.get(imageId);
        if (source == null) {
            throw new IllegalArgumentException("Imagen desconocida: " + imageId);
        }
        return source;
    }

    private ZoomLevel findZoomLevel(ImageSource source, ViewRequest request) {
        List<ZoomLevel> levels = ZoomCalculator.calculate(
                source.width(),
                source.height(),
                request.viewportWidth(),
                request.viewportHeight()
        );
        if (request.zoomIndex() >= levels.size()) {
            throw new IllegalArgumentException(
                    "Zoom invalido; la imagen tiene niveles 0 a " + (levels.size() - 1)
            );
        }
        return levels.get(request.zoomIndex());
    }

    private void validateGeneratedChunks(
            List<PlannedChunk> expected,
            List<RenderedChunk> generated
    ) {
        if (generated.size() != expected.size()) {
            throw new IllegalStateException("No se generaron todos los chunks faltantes");
        }
        for (int index = 0; index < expected.size(); index++) {
            if (!expected.get(index).equals(generated.get(index).chunk())) {
                throw new IllegalStateException("Los chunks generados no corresponden al plan");
            }
        }
    }

    private void validateCoverage(
            ViewRegion preparedRegion,
            ViewChunkPlan plan,
            List<RenderedChunk> rendered
    ) {
        long sourceArea = rendered.stream()
                .map(RenderedChunk::chunk)
                .mapToLong(chunk -> (long) chunk.sourceWidth() * chunk.sourceHeight())
                .sum();
        long outputArea = rendered.stream()
                .map(RenderedChunk::chunk)
                .mapToLong(chunk -> (long) chunk.outputWidth() * chunk.outputHeight())
                .sum();

        long expectedSourceArea = (long) preparedRegion.width() * preparedRegion.height();
        long expectedOutputArea = (long) plan.renderedWidth() * plan.renderedHeight();
        if (rendered.size() != plan.chunks().size()
                || rendered.stream().anyMatch(chunk -> chunk == null)
                || sourceArea != expectedSourceArea
                || outputArea != expectedOutputArea) {
            throw new IllegalStateException("Los chunks no cubren exactamente el area preparada");
        }
    }

    private void ensureNotCancelled() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("La generacion fue cancelada antes de publicar su cache");
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    @Override
    public void close() {
        workers.close();
    }
}
