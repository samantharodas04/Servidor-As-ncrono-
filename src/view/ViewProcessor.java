package view;

import cache.ChunkCache;
import cache.ChunkCacheKey;
import image.ImageSource;
import image.PreparedSourceStore;
import image.PreparedView;
import image.RenderedChunk;
import image.VipsChunkRenderer;
import image.VipsViewPreparer;
import worker.ChunkWorkerPool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;

/** Convierte VIEW en chunks JPEG o PNG; no conoce el navegador ni el WebSocket. */
public final class ViewProcessor implements AutoCloseable {
    private static final long DEFAULT_CACHE_BYTES = 32L * 1024L * 1024L;
    // Bajo 1/4, preparar toda la ventana del BigTIFF retrasa el primer chunk.
    private static final double INDIVIDUAL_TIFF_MAX_SCALE = 0.25;

    private final Map<String, ImageSource> sourcesById;
    private final VipsViewPreparer viewPreparer;
    private final PreparedSourceStore sourceStore;
    private final ChunkWorkerPool workers;
    private final ChunkCache cache;
    private final int normalJpegQuality;

    public ViewProcessor(List<ImageSource> sources, int workerCount) {
        this(sources, workerCount, DEFAULT_CACHE_BYTES);
    }

    public ViewProcessor(List<ImageSource> sources, int workerCount, long maximumCacheBytes) {
        this(sources, workerCount, maximumCacheBytes, 85);
    }

    public ViewProcessor(List<ImageSource> sources, int workerCount, long maximumCacheBytes,
                         int normalJpegQuality) {
        if (normalJpegQuality < 1 || normalJpegQuality > 100) {
            throw new IllegalArgumentException("Calidad JPEG fuera de rango");
        }
        this.normalJpegQuality = normalJpegQuality;
        this.sourcesById = indexSources(sources);
        this.viewPreparer = new VipsViewPreparer();
        this.sourceStore = new PreparedSourceStore(Path.of("images", "processed"));
        this.workers = new ChunkWorkerPool(workerCount, new VipsChunkRenderer());
        this.cache = new ChunkCache(maximumCacheBytes);
    }

    public ViewResult render(ViewRequest request) throws IOException {
        return render(request, new ViewProgress() { });
    }

    /** Anuncia la vista y entrega cada chunk conforme termina, antes del resultado final. */
    public ViewResult render(ViewRequest request, ViewProgress progress) throws IOException {
        Objects.requireNonNull(progress, "progress");
        // Dentro del paso 6: calcula zoom/chunks y consulta la cache antes de abrir pixeles.
        long startedAt = System.nanoTime();
        ImageSource source = findSource(request.imageId());
        ensureSourceUnchanged(source);
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
        // Antes de 1:1 envia JPEG; desde 1:1, PNG evita otra perdida de detalle.
        boolean losslessChunks = zoomLevel.scale() >= 1.0;

        Map<PlannedChunk, RenderedChunk> available = new LinkedHashMap<>();
        List<PlannedChunk> missing = new ArrayList<>();
        Set<CachedChunk> claimed = Set.copyOf(request.cachedChunks());
        Set<PlannedChunk> reused = new HashSet<>();
        for (PlannedChunk chunk : completePlan.chunks()) {
            if (claimed.contains(new CachedChunk(chunk.column(), chunk.row(),
                    chunk.outputWidth(), chunk.outputHeight()))) {
                reused.add(chunk);
                continue;
            }
            byte[] cachedBytes = cache.get(cacheKey(source, request, stablePlan, chunk));
            if (cachedBytes == null) {
                missing.add(chunk);
            } else {
                available.put(chunk, new RenderedChunk(chunk, cachedBytes, losslessChunks));
            }
        }

        int cacheHits = available.size();
        long preparationMillis = 0;
        long chunkMillis = 0;
        boolean individualPreparation = false;
        StableChunkPlan generationPlan = null;
        if (!missing.isEmpty()) {
            // La geometria se conoce antes de abrir el derivado o generar pixeles.
            ensureNotCancelled();
            generationPlan = ChunkPreparationPlanner.forMissingChunks(
                    stablePlan, missing
            );
        }

        ensureNotCancelled();
        progress.onStart(request, stablePlan.visibleRegion(), completePlan.chunks().size());
        Map<PlannedChunk, Integer> indexByChunk = new LinkedHashMap<>();
        for (int index = 0; index < completePlan.chunks().size(); index++) {
            PlannedChunk chunk = completePlan.chunks().get(index);
            indexByChunk.put(chunk, index);
            if (reused.contains(chunk)) {
                ensureNotCancelled();
                progress.onReference(request, index, chunk);
                continue;
            }
            RenderedChunk cached = available.get(chunk);
            if (cached != null) {
                ensureNotCancelled();
                progress.onChunk(request, index, cached);
            }
        }

        if (!missing.isEmpty()) {
            // START ya salio: la fuente puede tardar o fallar sin ocultar la solicitud.
            PreparedSourceStore.ResolvedSource renderSource = sourceStore.resolve(
                    source, stablePlan.scaleX(), stablePlan.scaleY()
            );
            // La vista temporal cubre los faltantes; los workers avisan al acabar cada uno.
            long preparationStartedAt = System.nanoTime();
            long preparationNanos;
            long chunkNanos;
            Map<PlannedChunk, RenderedChunk> generatedByChunk = new LinkedHashMap<>();
            ChunkWorkerPool.ChunkSink deliver = rendered -> {
                ensureNotCancelled();
                Integer index = indexByChunk.get(rendered.chunk());
                if (index == null || generatedByChunk.putIfAbsent(
                        rendered.chunk(), rendered) != null) {
                    throw new IOException("Worker entrego un chunk inesperado");
                }
                available.put(rendered.chunk(), rendered);
                progress.onChunk(request, index, rendered);
            };
            boolean individualTiff = renderSource.path().getFileName().toString()
                    .endsWith(".tiles.tif")
                    && Math.max(stablePlan.scaleX(), stablePlan.scaleY())
                    < INDIVIDUAL_TIFF_MAX_SCALE
                    && missing.size() > 1;
            if (individualTiff) {
                individualPreparation = true;
                // Dos workers como maximo leen regiones independientes; el primer chunk sale pronto.
                ChunkWorkerPool.ChunkTimes times = workers.renderIndividuallyAsCompleted(
                        renderSource.path(), stablePlan, missing,
                        renderSource.ratioX(), renderSource.ratioY(),
                        normalJpegQuality, losslessChunks, deliver
                );
                preparationNanos = times.preparationNanos();
                chunkNanos = times.renderNanos();
            } else {
                try (PreparedView preparedView = viewPreparer.prepare(
                        renderSource.path(), generationPlan,
                        renderSource.ratioX(), renderSource.ratioY()
                )) {
                    preparationNanos = System.nanoTime() - preparationStartedAt;
                    ensureNotCancelled();
                    long chunksStartedAt = System.nanoTime();
                    workers.renderAsCompleted(preparedView.path(), generationPlan.chunkPlan(),
                            normalJpegQuality, losslessChunks, deliver);
                    chunkNanos = System.nanoTime() - chunksStartedAt;
                }
            }
            preparationMillis = preparationNanos / 1_000_000L;
            chunkMillis = chunkNanos / 1_000_000L;

            ensureNotCancelled();
            List<RenderedChunk> generated = missing.stream()
                    .map(generatedByChunk::get).toList();
            validateGeneratedChunks(missing, generated);

            Map<ChunkCacheKey, byte[]> completedBatch = new LinkedHashMap<>();
            for (RenderedChunk rendered : generated) {
                completedBatch.put(
                        cacheKey(source, request, stablePlan, rendered.chunk()),
                        rendered.bytes()
                );
            }
            double costMillisPerChunk = Math.max(0.001,
                    (preparationNanos + chunkNanos) / 1_000_000.0 / missing.size());
            cache.putAll(completedBatch, costMillisPerChunk);
        }

        List<RenderedChunk> orderedChunks = completePlan.chunks().stream()
                .filter(chunk -> !reused.contains(chunk))
                .map(available::get)
                .toList();
        validateCoverage(stablePlan.preparedRegion(), completePlan, available, reused);
        return new ViewResult(
                request,
                source,
                zoomLevel,
                stablePlan.visibleRegion(),
                stablePlan.preparedRegion(),
                completePlan,
                orderedChunks,
                reused.size(),
                cacheHits,
                missing.size(),
                individualPreparation,
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

    private void ensureSourceUnchanged(ImageSource source) throws IOException {
        if (Files.size(source.path()) != source.sizeBytes()
                || Files.getLastModifiedTime(source.path()).toMillis()
                        != source.modifiedMillis()) {
            throw new IOException("El original cambio; reinicie el servidor para actualizar "
                    + "el catalogo: " + source.fileName());
        }
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
            Map<PlannedChunk, RenderedChunk> rendered,
            Set<PlannedChunk> reused
    ) {
        long sourceArea = plan.chunks().stream()
                .mapToLong(chunk -> (long) chunk.sourceWidth() * chunk.sourceHeight())
                .sum();
        long outputArea = plan.chunks().stream()
                .mapToLong(chunk -> (long) chunk.outputWidth() * chunk.outputHeight())
                .sum();

        long expectedSourceArea = (long) preparedRegion.width() * preparedRegion.height();
        long expectedOutputArea = (long) plan.renderedWidth() * plan.renderedHeight();
        if (rendered.size() + reused.size() != plan.chunks().size()
                || plan.chunks().stream().anyMatch(chunk ->
                        !reused.contains(chunk) && !rendered.containsKey(chunk))
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
