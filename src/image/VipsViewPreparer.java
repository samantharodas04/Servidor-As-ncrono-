package image;

import view.StableChunkPlan;
import view.ViewChunkPlan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Abre el original una vez y prepara el rectangulo de tiles visibles. */
public final class VipsViewPreparer {
    private static final long TIMEOUT_SECONDS = 60;
    private static final long MAX_VIEW_PIXELS = 16L * 1024L * 1024L;
    private static final long MAX_PREPARED_BYTES = 128L * 1024L * 1024L;
    private static final int[] JPEG_SHRINK_FACTORS = {8, 4, 2};

    public PreparedView prepare(Path source, StableChunkPlan stablePlan) throws IOException {
        Path normalizedSource = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedSource)) {
            throw new IOException("La imagen original no existe: " + source.getFileName());
        }

        ViewChunkPlan chunkPlan = stablePlan.chunkPlan();
        long pixels = (long) chunkPlan.renderedWidth() * chunkPlan.renderedHeight();
        if (pixels > MAX_VIEW_PIXELS) {
            throw new IOException("La vista solicitada excede el limite de pixeles: " + pixels);
        }

        int decoderShrink = chooseDecoderShrink(
                normalizedSource, stablePlan.scaleX(), stablePlan.scaleY()
        );
        Path preparedPath = Files.createTempFile("prepared-view-", ".v");
        boolean completed = false;
        try {
            runVips(normalizedSource, preparedPath, stablePlan, decoderShrink);

            long preparedBytes = Files.size(preparedPath);
            if (preparedBytes <= 0 || preparedBytes > MAX_PREPARED_BYTES) {
                throw new IOException(
                        "Tamano de vista temporal fuera del limite: " + preparedBytes
                );
            }

            completed = true;
            return new PreparedView(
                    preparedPath,
                    chunkPlan.renderedWidth(),
                    chunkPlan.renderedHeight()
            );
        } finally {
            if (!completed) {
                Files.deleteIfExists(preparedPath);
            }
        }
    }

    private void runVips(
            Path source,
            Path output,
            StableChunkPlan stablePlan,
            int decoderShrink
    ) throws IOException {
        ViewChunkPlan chunkPlan = stablePlan.chunkPlan();
        double decoderScaleX = stablePlan.scaleX() * decoderShrink;
        double decoderScaleY = stablePlan.scaleY() * decoderShrink;
        double decoderX = stablePlan.sourceOriginX() / decoderShrink;
        double decoderY = stablePlan.sourceOriginY() / decoderShrink;

        String inputWithOptions = decoderShrink == 1
                ? source.toString()
                : source + "[shrink=" + decoderShrink + "]";
        String matrix = decoderScaleX + " 0 0 " + decoderScaleY;

        System.out.printf(
                "Preparando tiles %dx%d desde %s con reduccion JPEG %dx%n",
                chunkPlan.renderedWidth(),
                chunkPlan.renderedHeight(),
                source.getFileName(),
                decoderShrink
        );

        Process process = new ProcessBuilder(
                "vips",
                "affine",
                inputWithOptions,
                output.toString(),
                matrix,
                "--idx=" + (-decoderX),
                "--idy=" + (-decoderY),
                "--oarea=0 0 " + chunkPlan.renderedWidth()
                        + " " + chunkPlan.renderedHeight()
        ).redirectErrorStream(true).start();

        waitFor(process, "preparar los tiles visibles");
    }

    private void waitFor(Process process, String operation) throws IOException {
        final boolean completed;
        try {
            completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Se interrumpio la operacion: " + operation, e);
        }

        if (!completed) {
            process.destroyForcibly();
            throw new IOException("libvips excedio el tiempo limite al " + operation);
        }

        String processOutput = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8
        ).trim();
        if (process.exitValue() != 0) {
            throw new IOException("libvips no pudo " + operation + ": " + processOutput);
        }
    }

    private int chooseDecoderShrink(Path source, double scaleX, double scaleY) {
        if (!isJpeg(source)) {
            return 1;
        }

        double largestScale = Math.max(scaleX, scaleY);
        for (int factor : JPEG_SHRINK_FACTORS) {
            if (largestScale * factor <= 1.0) {
                return factor;
            }
        }
        return 1;
    }

    private boolean isJpeg(Path source) {
        String filename = source.getFileName().toString().toLowerCase(Locale.ROOT);
        return filename.endsWith(".jpg") || filename.endsWith(".jpeg");
    }
}
