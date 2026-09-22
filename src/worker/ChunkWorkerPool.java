package worker;

import image.ChunkRenderer;
import image.RenderedChunk;
import view.PlannedChunk;
import view.ViewChunkPlan;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** Ejecuta trabajos de chunks usando una cantidad fija y limitada de workers. */
public final class ChunkWorkerPool implements AutoCloseable {
    private final ExecutorService executor;
    private final ChunkRenderer renderer;

    public ChunkWorkerPool(int workerCount, ChunkRenderer renderer) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("Debe existir al menos un worker");
        }

        this.renderer = renderer;
        AtomicInteger workerNumber = new AtomicInteger(1);
        this.executor = Executors.newFixedThreadPool(workerCount, task -> {
            Thread thread = new Thread(task);
            thread.setName("chunk-worker-" + workerNumber.getAndIncrement());
            return thread;
        });
    }

    public List<RenderedChunk> renderAll(Path preparedView, ViewChunkPlan plan)
            throws IOException {
        List<Future<RenderedChunk>> pending = new ArrayList<>(plan.chunks().size());
        for (PlannedChunk originalChunk : plan.chunks()) {
            pending.add(executor.submit(() -> {
                System.out.printf(
                        "%s recorta chunk [%d,%d]%n",
                        Thread.currentThread().getName(),
                        originalChunk.column(), originalChunk.row()
                );
                PlannedChunk localChunk = toPreparedViewChunk(originalChunk, plan);
                byte[] jpeg = renderer.render(preparedView, localChunk);
                return new RenderedChunk(originalChunk, jpeg);
            }));
        }

        List<RenderedChunk> rendered = new ArrayList<>(plan.chunks().size());
        try {
            for (Future<RenderedChunk> future : pending) {
                rendered.add(future.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelPending(pending);
            throw new IOException("Se interrumpio la generacion de la vista", e);
        } catch (ExecutionException e) {
            cancelPending(pending);
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Un worker no pudo generar su chunk", cause);
        }

        return List.copyOf(rendered);
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
