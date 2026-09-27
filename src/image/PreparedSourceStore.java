package image;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Mantiene derivados opcionales de un original, sin crear una piramide de tiles.
 * El original sigue siendo la identidad publica y la clave de la cache.
 */
public final class PreparedSourceStore {
    private static final long DIRECT_PIXEL_LIMIT = 100_000_000L;
    private static final int OVERVIEW_MAX_EDGE = 4096;
    private static final long PREPARATION_TIMEOUT_MINUTES = 120;

    private final Path directory;
    private final ImageHeaderReader headerReader = new ImageHeaderReader();

    public PreparedSourceStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public ResolvedSource resolve(ImageSource original, double scaleX, double scaleY)
            throws IOException {
        if (isJpeg(original.path())) {
            return new ResolvedSource(original.path(), 1.0, 1.0);
        }
        if (!Double.isFinite(scaleX) || !Double.isFinite(scaleY)
                || scaleX <= 0 || scaleY <= 0) {
            throw new IllegalArgumentException("La escala de la vista debe ser positiva");
        }

        Path overview = overviewPath(original);
        if (Files.isRegularFile(overview)) {
            ImageDimensions dimensions = headerReader.read(overview);
            double ratioX = (double) dimensions.width() / original.width();
            double ratioY = (double) dimensions.height() / original.height();
            if (ratioX <= 0 || ratioY <= 0 || ratioX > 1 || ratioY > 1) {
                throw new IOException("Vista previa invalida: " + overview.getFileName());
            }
            if (scaleX <= ratioX && scaleY <= ratioY) {
                return new ResolvedSource(overview, ratioX, ratioY);
            }
        }

        Path tiled = tiledPath(original);
        if (Files.isRegularFile(tiled)) {
            ImageDimensions dimensions = headerReader.read(tiled);
            if (dimensions.width() != original.width()
                    || dimensions.height() != original.height()) {
                throw new IOException("Fuente mosaico invalida: " + tiled.getFileName());
            }
            return new ResolvedSource(tiled, 1.0, 1.0);
        }

        long pixels = (long) original.width() * original.height();
        if (pixels > DIRECT_PIXEL_LIMIT) {
            throw new IOException(
                    "La imagen gigante necesita preparacion: make prepare-image IMAGE="
                            + original.id()
            );
        }
        return new ResolvedSource(original.path(), 1.0, 1.0);
    }

    /** Prepara primero la vista general y luego una fuente mosaico de resolucion completa. */
    public void prepare(ImageSource original) throws IOException {
        prepareOverview(original);
        if (isJpeg(original.path())) {
            return;
        }
        Path tiled = tiledPath(original);
        if (!Files.isRegularFile(tiled)) {
            System.out.println("Preparando BigTIFF mosaico sin piramide: " + original.fileName());
            build(original, tiled, ".tif", new String[] {
                    "vips", "--vips-concurrency=2", "tiffsave",
                    original.path().toString(), "%OUTPUT%",
                    "--tile", "--tile-width=512", "--tile-height=512",
                    "--bigtiff", "--compression=deflate"
            });
            System.out.println("BigTIFF mosaico listo: " + tiled);
        } else {
            System.out.println("BigTIFF mosaico existente: " + tiled);
        }
    }

    /** Suficiente para la vista general; el zoom detallado necesita ademas el BigTIFF. */
    public void prepareOverview(ImageSource original) throws IOException {
        if (isJpeg(original.path())) {
            System.out.println("JPEG usa reduccion al abrir; no necesita preparacion: "
                    + original.fileName());
            return;
        }
        Files.createDirectories(directory);

        Path overview = overviewPath(original);
        if (!Files.isRegularFile(overview)) {
            System.out.println("Preparando vista general: " + original.fileName());
            build(original, overview, ".jpg", new String[] {
                    "vipsthumbnail", "--vips-concurrency=2",
                    "--size=" + OVERVIEW_MAX_EDGE,
                    "--output=%OUTPUT%",
                    original.path().toString()
            });
            System.out.println("Vista general lista: " + overview);
        } else {
            System.out.println("Vista general existente: " + overview);
        }
    }

    public Path overviewPath(ImageSource original) {
        return directory.resolve(versionStem(original) + ".overview.jpg");
    }

    public Path tiledPath(ImageSource original) {
        return directory.resolve(versionStem(original) + ".tiles.tif");
    }

    private String versionStem(ImageSource original) {
        return original.id() + "-" + original.sizeBytes() + "-"
                + original.modifiedMillis();
    }

    private boolean isJpeg(Path source) {
        String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jpg") || name.endsWith(".jpeg");
    }

    private void build(ImageSource original, Path target, String suffix, String[] command)
            throws IOException {
        Path temporary = Files.createTempFile(directory, "preparing-", suffix);
        boolean published = false;
        try {
            for (int index = 0; index < command.length; index++) {
                command[index] = command[index].replace("%OUTPUT%", temporary.toString());
            }
            Process process = new ProcessBuilder(command).inheritIO().start();
            final boolean finished;
            try {
                finished = process.waitFor(PREPARATION_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("Preparacion interrumpida", e);
            }
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("La preparacion excedio el tiempo limite");
            }
            if (process.exitValue() != 0 || Files.size(temporary) == 0) {
                throw new IOException("libvips no pudo preparar " + original.fileName()
                        + " (salida " + process.exitValue() + ")");
            }
            ImageDimensions dimensions = headerReader.read(temporary);
            if (".tif".equals(suffix)) {
                if (dimensions.width() != original.width()
                        || dimensions.height() != original.height()) {
                    throw new IOException("El BigTIFF no conserva las dimensiones originales");
                }
            } else if (dimensions.width() > OVERVIEW_MAX_EDGE
                    || dimensions.height() > OVERVIEW_MAX_EDGE) {
                throw new IOException("La vista general excede el limite previsto");
            }
            if (Files.size(original.path()) != original.sizeBytes()
                    || Files.getLastModifiedTime(original.path()).toMillis()
                            != original.modifiedMillis()) {
                throw new IOException("El original cambio durante la preparacion");
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            published = true;
        } finally {
            if (!published) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    public record ResolvedSource(Path path, double ratioX, double ratioY) {
        public ResolvedSource {
            if (path == null || !Double.isFinite(ratioX) || !Double.isFinite(ratioY)
                    || ratioX <= 0 || ratioY <= 0) {
                throw new IllegalArgumentException("Fuente de render invalida");
            }
        }
    }
}
