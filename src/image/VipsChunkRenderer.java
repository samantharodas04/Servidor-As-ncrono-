package image;

import view.PlannedChunk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Recorta un chunk JPEG desde una vista pequena previamente preparada. */
public final class VipsChunkRenderer implements ChunkRenderer {
    private static final long TIMEOUT_SECONDS = 15;
    private static final long MAX_JPEG_BYTES = 8L * 1024L * 1024L;
    private static final int JPEG_QUALITY = 85;

    @Override
    public byte[] render(Path source, PlannedChunk chunk) throws IOException {
        Path normalizedSource = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedSource)) {
            throw new IOException("La vista preparada no existe");
        }

        Path temporaryJpeg = Files.createTempFile("image-chunk-", ".jpg");
        try {
            runVips(normalizedSource, temporaryJpeg, chunk);

            long jpegSize = Files.size(temporaryJpeg);
            if (jpegSize <= 0 || jpegSize > MAX_JPEG_BYTES) {
                throw new IOException("Tamano JPEG fuera del limite: " + jpegSize + " bytes");
            }

            byte[] jpeg = Files.readAllBytes(temporaryJpeg);
            validateJpeg(jpeg);
            return jpeg;
        } finally {
            Files.deleteIfExists(temporaryJpeg);
        }
    }

    private void runVips(Path source, Path output, PlannedChunk chunk) throws IOException {
        String outputWithOptions = output + "[Q=" + JPEG_QUALITY + ",optimize-coding]";
        Process process = new ProcessBuilder(
                "vips",
                "crop",
                source.toString(),
                outputWithOptions,
                Integer.toString(chunk.sourceX()),
                Integer.toString(chunk.sourceY()),
                Integer.toString(chunk.sourceWidth()),
                Integer.toString(chunk.sourceHeight())
        ).redirectErrorStream(true).start();

        final boolean completed;
        try {
            completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Se interrumpio la generacion del chunk", e);
        }

        if (!completed) {
            process.destroyForcibly();
            throw new IOException("libvips excedio el tiempo limite al recortar el chunk");
        }

        String processOutput = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8
        ).trim();
        if (process.exitValue() != 0) {
            throw new IOException("libvips no pudo generar el chunk: " + processOutput);
        }
    }

    private void validateJpeg(byte[] jpeg) throws IOException {
        if (jpeg.length < 4
                || (jpeg[0] & 0xFF) != 0xFF
                || (jpeg[1] & 0xFF) != 0xD8
                || (jpeg[jpeg.length - 2] & 0xFF) != 0xFF
                || (jpeg[jpeg.length - 1] & 0xFF) != 0xD9) {
            throw new IOException("libvips no produjo un JPEG valido");
        }
    }
}
