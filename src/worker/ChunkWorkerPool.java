package worker;

import image.ChunkRenderer;
import image.RenderedChunk;
import view.PlannedChunk;
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

/** Recorta en paralelo la vista temporal; cada resultado sale como JPEG o PNG. */
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
        CompletionService<RenderedChunk> completed = new ExecutorCompletionService<>(executor);
        List<Future<RenderedChunk>> pending = new ArrayList<>(plan.chunks().size());
        int next = 0;
        int total = plan.chunks().size();
        boolean allDelivered = false;
        try {
            while (next < total && next < workerCount) {
                pending.add(submit(completed, preparedView, plan,
                        plan.chunks().get(next++), jpegQuality, png));
            }
            for (int delivered = 0; delivered < total; delivered++) {
                Future<RenderedChunk> finished = completed.take();
                pending.remove(finished);
                sink.accept(finished.get());
                if (next < total) {
                    pending.add(submit(completed, preparedView, plan,
                            plan.chunks().get(next++), jpegQuality, png));
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

    private Future<RenderedChunk> submit(
            CompletionService<RenderedChunk> completed, Path preparedView, ViewChunkPlan plan,
            PlannedChunk originalChunk, int jpegQuality, boolean png
    ) {
        return completed.submit(() -> {
            System.out.printf("%s recorta chunk [%d,%d]%n",
                    Thread.currentThread().getName(),
                    originalChunk.column(), originalChunk.row());
            PlannedChunk localChunk = toPreparedViewChunk(originalChunk, plan);
            byte[] bytes = renderer.render(preparedView, localChunk, jpegQuality, png);
            return new RenderedChunk(originalChunk, bytes, png);
        });
    }

    @FunctionalInterface
    public interface ChunkSink {
        void accept(RenderedChunk chunk) throws IOException;
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

    private void cancelPending(List<Future<RenderedChunk>> pending) {
        for (Future<RenderedChunk> future : pending) {
            future.cancel(true);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
