package view;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Conserva una sola generacion activa y cancela la anterior al recibir otra. */
public final class ViewCoordinator implements AutoCloseable {
    private static final long CLOSE_TIMEOUT_SECONDS = 5;

    private final ViewProcessor processor;
    private final boolean closesProcessor;
    private final ExecutorService requestExecutor;
    private Future<ViewResult> activeTask;
    private long latestGenerationId;
    private boolean closed;

    public ViewCoordinator(ViewProcessor processor) {
        this(processor, true);
    }

    private ViewCoordinator(ViewProcessor processor, boolean closesProcessor) {
        this.processor = Objects.requireNonNull(processor, "processor");
        this.closesProcessor = closesProcessor;
        this.requestExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task);
            thread.setName("view-coordinator");
            return thread;
        });
    }

    /** Crea un coordinador de sesion que no cierra el procesador compartido. */
    public static ViewCoordinator usingSharedProcessor(ViewProcessor processor) {
        return new ViewCoordinator(processor, false);
    }

    public synchronized Future<ViewResult> submit(ViewRequest request) {
        return submit(request, result -> { }, failure -> { });
    }

    /** Ejecuta la vista y notifica su resultado sin bloquear el hilo del servidor. */
    public synchronized Future<ViewResult> submit(
            ViewRequest request,
            Consumer<ViewResult> onSuccess,
            Consumer<Throwable> onFailure
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(onSuccess, "onSuccess");
        Objects.requireNonNull(onFailure, "onFailure");
        if (closed) {
            throw new IllegalStateException("El coordinador ya esta cerrado");
        }
        if (request.generationId() <= latestGenerationId) {
            throw new IllegalArgumentException(
                    "La generacion debe ser mayor que " + latestGenerationId
            );
        }

        if (activeTask != null && !activeTask.isDone()) {
            System.out.printf(
                    "Cancelando generacion %d; llega generacion %d%n",
                    latestGenerationId, request.generationId()
            );
            activeTask.cancel(true);
        }

        latestGenerationId = request.generationId();
        activeTask = requestExecutor.submit(() -> {
            try {
                ViewResult result = processor.render(request);
                onSuccess.accept(result);
                return result;
            } catch (Exception failure) {
                onFailure.accept(failure);
                throw failure;
            }
        });
        return activeTask;
    }

    public synchronized boolean isCurrent(long generationId) {
        return generationId == latestGenerationId;
    }

    @Override
    public void close() {
        Future<ViewResult> taskToCancel;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            taskToCancel = activeTask;
        }

        if (taskToCancel != null && !taskToCancel.isDone()) {
            taskToCancel.cancel(true);
        }
        requestExecutor.shutdownNow();
        if (!closesProcessor) {
            return;
        }
        try {
            requestExecutor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            processor.close();
        }
    }
}
