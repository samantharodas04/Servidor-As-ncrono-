package image;

import view.PlannedChunk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Recorta un chunk desde una vista pequena previamente preparada. */
public final class VipsChunkRenderer implements ChunkRenderer {
    private static final long TIMEOUT_SECONDS = 15;
    private static final long MAX_CHUNK_BYTES = 8L * 1024L * 1024L;
    @Override
    public byte[] render(Path source, PlannedChunk chunk, int jpegQuality, boolean png)
            throws IOException {
        if (jpegQuality < 1 || jpegQuality > 100) {
            throw new IllegalArgumentException("Calidad JPEG fuera de rango");
        }
        Path normalizedSource = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedSource)) {
            throw new IOException("La vista preparada no existe");
        }

        // /tmp solo guarda este chunk mientras libvips lo codifica; se borra al leerlo.
        Path temporaryImage = Files.createTempFile("image-chunk-", png ? ".png" : ".jpg");
        try {
            runVips(normalizedSource, temporaryImage, chunk, jpegQuality, png);

            long imageSize = Files.size(temporaryImage);
            if (imageSize <= 0 || imageSize > MAX_CHUNK_BYTES) {
                throw new IOException("Tamano de chunk fuera del limite: " + imageSize + " bytes");
            }

            byte[] bytes = Files.readAllBytes(temporaryImage);
            if (png) validatePng(bytes);
            else validateJpeg(bytes);
            return bytes;
        } finally {
            Files.deleteIfExists(temporaryImage);
        }
    }

    private void runVips(Path source, Path output, PlannedChunk chunk, int jpegQuality,
                         boolean png)
            throws IOException {
        String outputWithOptions = png ? output + "[compression=6]"
                : output + "[Q=" + jpegQuality + ",optimize-coding]";
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

    private void validatePng(byte[] png) throws IOException {
        byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        if (png.length < signature.length) {
            throw new IOException("libvips no produjo un PNG valido");
        }
        for (int i = 0; i < signature.length; i++) {
            if (png[i] != signature[i]) {
                throw new IOException("libvips no produjo un PNG valido");
            }
        }
    }
}
