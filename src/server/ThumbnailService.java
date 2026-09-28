package server;

import image.ImageSource;
import image.PreparedSourceStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Miniatura HTTP de la imagen elegida; nunca abre un original gigante sin vista general. */
final class ThumbnailService implements AutoCloseable {
    private static final long DIRECT_SOURCE_LIMIT = 20L * 1024L * 1024L;
    private static final int MAX_THUMB_BYTES = 512 * 1024;
    private final PreparedSourceStore prepared =
            new PreparedSourceStore(Path.of("images", "processed"));
    private final ExecutorService workers = new ThreadPoolExecutor(
            2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8));

    CompletableFuture<byte[]> load(ImageSource image) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return generate(image);
            } catch (IOException error) {
                throw new RuntimeException(error);
            }
        }, workers);
    }

    private byte[] generate(ImageSource image) throws IOException {
        Path overview = prepared.overviewPath(image);
        Path source = Files.isRegularFile(overview) ? overview : image.path();
        if (source.equals(image.path()) && (image.sizeBytes() > DIRECT_SOURCE_LIMIT
                || (long) image.width() * image.height() > 100_000_000L)) {
            throw new IOException("La imagen necesita una vista general preparada");
        }

        Path temporary = Files.createTempFile("pai-thumbnail-", ".jpg");
        try {
            Process process = new ProcessBuilder(
                    "vipsthumbnail", "--vips-concurrency=2", "--size=320x200",
                    "--output=" + temporary, source.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            boolean finished;
            try {
                finished = process.waitFor(15, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("Miniatura interrumpida", error);
            }
            if (!finished) process.destroyForcibly();
            if (!finished || process.exitValue() != 0
                    || Files.size(temporary) == 0
                    || Files.size(temporary) > MAX_THUMB_BYTES) {
                throw new IOException("No se pudo generar una miniatura pequena");
            }
            return Files.readAllBytes(temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }
}
