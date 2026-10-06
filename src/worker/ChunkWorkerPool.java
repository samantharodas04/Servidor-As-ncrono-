package worker;

import image.ChunkRenderer;
import image.PreparedView;
import image.RenderedChunk;
import image.VipsViewPreparer;
import view.ChunkPreparationPlanner;
import view.PlannedChunk;
import view.StableChunkPlan;
import view.ViewChunkPlan;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** Prepara o recorta con workers limitados; entrega cada JPEG o PNG al terminar. */
public final class ChunkWorkerPool implements AutoCloseable {
    private final ExecutorService executor;
    private final ChunkRenderer renderer;
    private final int workerCount;

    public ChunkWorkerPool(int workerCount, ChunkRenderer renderer) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("Debe existir al menos un worker");
        }

        this.renderer = renderer;
        this.workerCount = workerCount;
        AtomicInteger workerNumber = new AtomicInteger(1);
        this.executor = Executors.newFixedThreadPool(workerCount, task -> {
            Thread thread = new Thread(task);
            thread.setName("chunk-worker-" + workerNumber.getAndIncrement());
            return thread;
        });
    }

    public List<RenderedChunk> renderAll(Path preparedView, ViewChunkPlan plan,
                                         int jpegQuality, boolean png)
            throws IOException {
        Map<PlannedChunk, RenderedChunk> byChunk = new HashMap<>();
        renderAsCompleted(preparedView, plan, jpegQuality, png,
                rendered -> byChunk.put(rendered.chunk(), rendered));
        return plan.chunks().stream().map(byChunk::get).toList();
    }

    /** Entrega cada resultado terminado; mantiene a lo sumo workerCount tareas en vuelo. */
    public void renderAsCompleted(Path preparedView, ViewChunkPlan plan,
                                  int jpegQuality, boolean png, ChunkSink sink)
            throws IOException {
        renderTasks(plan.chunks(), chunk -> {
            System.out.printf("%s recorta chunk [%d,%d]%n",
                    Thread.currentThread().getName(), chunk.column(), chunk.row());
            PlannedChunk local = toPreparedViewChunk(chunk, plan);
            return new PreparedChunk(new RenderedChunk(chunk,
                    renderer.render(preparedView, local, jpegQuality, png), png), 0, 0);
        }, completed -> sink.accept(completed.rendered()));
    }

    /** Para TIFF reducido, prepara y codifica cada chunk sin esperar el area completa. */
    public ChunkTimes renderIndividuallyAsCompleted(
            Path source, StableChunkPlan fullPlan, List<PlannedChunk> missing,
            double ratioX, double ratioY, int jpegQuality, boolean png, ChunkSink sink
    ) throws IOException {
        long[] nanos = new long[2];
        renderTasks(missing, chunk -> {
            StableChunkPlan one = ChunkPreparationPlanner.forMissingChunks(
                    fullPlan, List.of(chunk));
            long started = System.nanoTime();
            try (PreparedView prepared = new VipsViewPreparer().prepare(
                    source, one, ratioX, ratioY)) {
                long preparationNanos = System.nanoTime() - started;
                System.out.printf("%s recorta chunk [%d,%d]%n",
                        Thread.currentThread().getName(), chunk.column(), chunk.row());
                long renderingStarted = System.nanoTime();
                PlannedChunk local = toPreparedViewChunk(chunk, one.chunkPlan());
                byte[] bytes = renderer.render(prepared.path(), local, jpegQuality, png);
                return new PreparedChunk(new RenderedChunk(chunk, bytes, png),
                        preparationNanos, System.nanoTime() - renderingStarted);
            }
        }, completed -> {
            nanos[0] += completed.preparationNanos();
            nanos[1] += completed.renderNanos();
            sink.accept(completed.rendered());
        });
        return new ChunkTimes(nanos[0], nanos[1]);
    }

    private void renderTasks(List<PlannedChunk> chunks, ChunkTask task,
                             PreparedChunkSink sink) throws IOException {
        CompletionService<PreparedChunk> completed = new ExecutorCompletionService<>(executor);
        List<Future<PreparedChunk>> pending = new ArrayList<>(chunks.size());
        int next = 0;
        int total = chunks.size();
        boolean allDelivered = false;
        try {
            while (next < total && next < workerCount) {
                PlannedChunk chunk = chunks.get(next++);
                pending.add(completed.submit(() -> task.run(chunk)));
            }
            for (int delivered = 0; delivered < total; delivered++) {
                Future<PreparedChunk> finished = completed.take();
                pending.remove(finished);
                sink.accept(finished.get());
                if (next < total) {
                    PlannedChunk chunk = chunks.get(next++);
                    pending.add(completed.submit(() -> task.run(chunk)));
                }
            }
            allDelivered = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Se interrumpio la generacion de la vista", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Un worker no pudo generar su chunk", cause);
        } finally {
            if (!allDelivered) {
                cancelPending(pending);
            }
        }
    }

    @FunctionalInterface
    public interface ChunkSink {
        void accept(RenderedChunk chunk) throws IOException;
    }

    public record ChunkTimes(long preparationNanos, long renderNanos) {
    }

    private record PreparedChunk(RenderedChunk rendered,
                                 long preparationNanos, long renderNanos) {
    }

    @FunctionalInterface
    private interface ChunkTask {
        PreparedChunk run(PlannedChunk chunk) throws IOException;
    }

    @FunctionalInterface
    private interface PreparedChunkSink {
        void accept(PreparedChunk chunk) throws IOException;
    }

    private PlannedChunk toPreparedViewChunk(
            PlannedChunk original,
            ViewChunkPlan plan
    ) {
        int localX = original.canvasX() - plan.canvasX();
        int localY = original.canvasY() - plan.canvasY();
        return new PlannedChunk(
                original.column(),
                original.row(),
                localX,
                localY,
                original.outputWidth(),
                original.outputHeight(),
                localX,
                localY,
                original.outputWidth(),
                original.outputHeight()
        );
    }

    private void cancelPending(List<Future<PreparedChunk>> pending) {
        for (Future<PreparedChunk> future : pending) {
            future.cancel(true);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
