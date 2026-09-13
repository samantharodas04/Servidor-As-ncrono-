package image;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;

public class ImageManager {
    private static final long GIANT_THRESHOLD_BYTES = 200L * 1024L * 1024L;

    private final Path originalsDir;
    private final Path processedDir;
    private final int tileSize;
    private final Map<String, ImageMetadata> metadata = new LinkedHashMap<>();
    private final GiantImageProcessor giantProcessor;

    public ImageManager(String originalsDir, String processedDir, int tileSize) {
        this.originalsDir = Paths.get(originalsDir);
        this.processedDir = Paths.get(processedDir);
        this.tileSize = tileSize;
        this.giantProcessor = new GiantImageProcessor(this.processedDir, tileSize);
    }

    public synchronized void processAllImages() throws IOException {
        Files.createDirectories(originalsDir);
        Files.createDirectories(processedDir);

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(originalsDir)) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file) || !isSupportedImage(file)) continue;
                try {
                    loadOrProcess(file);
                } catch (Exception e) {
                    System.err.println("[WARN] No se pudo preparar " + file.getFileName() + ": " + e.getMessage());
                }
            }
        }
    }

    public synchronized ImageMetadata loadOrProcess(Path input) throws IOException {
        String id = imageId(input);
        Path metaFile = processedDir.resolve(id).resolve("metadata.json");

        if (Files.exists(metaFile)) {
            try {
                ImageMetadata cached = ImageMetadata.fromJson(Files.readString(metaFile, StandardCharsets.UTF_8));
                long size = Files.size(input);
                long modified = Files.getLastModifiedTime(input).toMillis();
                if (cached.tileSize == tileSize
                        && cached.sourceBytes == size
                        && cached.sourceLastModified == modified
                        && allLevelsComplete(id, cached.levels)) {
                    metadata.put(id, cached);
                    System.out.println("Imagen cacheada lista: " + id + " (" + cached.width + "x" + cached.height
                            + ", procesador=" + cached.processor + ")");
                    return cached;
                }
            } catch (Exception e) {
                System.err.println("[WARN] Metadata cacheada inválida para " + id + ": " + e.getMessage());
            }
        }

        return processImage(input);
    }

    public synchronized ImageMetadata processImage(Path input) throws IOException {
        String name = input.getFileName().toString().toLowerCase(Locale.ROOT);
        long bytes = Files.size(input);

        boolean giant = name.endsWith(".psb")
                || name.endsWith(".tif")
                || name.endsWith(".tiff")
                || bytes >= GIANT_THRESHOLD_BYTES
                || hasGiantDimensions(input);

        ImageMetadata meta;
        if (giant) {
            meta = giantProcessor.process(input, imageId(input));
        } else {
            meta = processSmallImageJava(input);
        }

        metadata.put(meta.id, meta);
        System.out.println("Imagen preparada: " + meta.id + " (" + meta.width + "x" + meta.height
                + "), niveles=" + meta.levels + ", procesador=" + meta.processor);
        return meta;
    }

    private ImageMetadata processSmallImageJava(Path input) throws IOException {
        BufferedImage original = ImageIO.read(input.toFile());
        if (original == null) {
            // Si ImageIO no entiende el formato, intentamos el procesador de disco.
            return giantProcessor.process(input, imageId(input));
        }

        String id = imageId(input);
        int maxDim = Math.max(original.getWidth(), original.getHeight());
        int levels = 1;
        int dim = maxDim;
        while (dim > tileSize) {
            dim = (int) Math.ceil(dim / 2.0);
            levels++;
        }

        Path imageRoot = processedDir.resolve(id);
        Files.createDirectories(imageRoot);

        for (int level = 0; level < levels; level++) {
            double scale = Math.pow(2, level - (levels - 1));
            int levelWidth = Math.max(1, (int) Math.round(original.getWidth() * scale));
            int levelHeight = Math.max(1, (int) Math.round(original.getHeight() * scale));

            BufferedImage scaled = new BufferedImage(levelWidth, levelHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(original, 0, 0, levelWidth, levelHeight, null);
            g.dispose();

            Path levelDir = imageRoot.resolve("level" + level);
            deleteTree(levelDir);
            Files.createDirectories(levelDir);

            int cols = (int) Math.ceil(levelWidth / (double) tileSize);
            int rows = (int) Math.ceil(levelHeight / (double) tileSize);

            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    int sx = x * tileSize;
                    int sy = y * tileSize;
                    int w = Math.min(tileSize, levelWidth - sx);
                    int h = Math.min(tileSize, levelHeight - sy);

                    BufferedImage tile = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                    Graphics2D tg = tile.createGraphics();
                    tg.drawImage(scaled, 0, 0, w, h, sx, sy, sx + w, sy + h, null);
                    tg.dispose();

                    ImageIO.write(tile, "jpg", levelDir.resolve(x + "_" + y + ".jpg").toFile());
                }
            }
            Files.writeString(levelDir.resolve(".complete"), "ok\n", StandardCharsets.UTF_8);
        }

        ImageMetadata meta = new ImageMetadata(
                id, original.getWidth(), original.getHeight(), tileSize, levels,
                input.getFileName().toString(), Files.size(input),
                Files.getLastModifiedTime(input).toMillis(), "java-imageio"
        );
        Files.writeString(imageRoot.resolve("metadata.json"), meta.toJson(), StandardCharsets.UTF_8);
        return meta;
    }

    // Lee únicamente el encabezado cuando ImageIO lo permite; no decodifica el raster completo.
    private boolean hasGiantDimensions(Path input) {
        try (ImageInputStream in = ImageIO.createImageInputStream(input.toFile())) {
            if (in == null) return false;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return false;
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                long pixels = (long) w * (long) h;
                return Math.max(w, h) > 12000 || pixels > 100_000_000L;
            } finally {
                reader.dispose();
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    public synchronized List<ImageMetadata> listImages() {
        return new ArrayList<>(metadata.values());
    }

    public synchronized ImageMetadata getMetadata(String id) {
        return metadata.get(id);
    }

    public byte[] getTile(String id, int level, int x, int y) throws IOException {
        ImageMetadata meta = metadata.get(id);
        if (meta == null) throw new FileNotFoundException("IMAGE_NOT_FOUND");
        if (level < 0 || level >= meta.levels) throw new IllegalArgumentException("INVALID_LEVEL");

        Path tile = processedDir.resolve(id)
                .resolve("level" + level)
                .resolve(x + "_" + y + ".jpg");
        if (!Files.exists(tile)) throw new FileNotFoundException("TILE_NOT_FOUND");
        return Files.readAllBytes(tile);
    }

    public boolean isImageMagickAvailable() {
        return giantProcessor.isImageMagickAvailable();
    }

    private boolean isSupportedImage(Path file) {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".tif") || n.endsWith(".tiff") || n.endsWith(".psb");
    }

    private boolean allLevelsComplete(String id, int levels) {
        for (int i = 0; i < levels; i++) {
            if (!Files.exists(processedDir.resolve(id).resolve("level" + i).resolve(".complete"))) {
                return false;
            }
        }
        return true;
    }

    private String imageId(Path input) {
        String filename = input.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        return sanitizeId(dot > 0 ? filename.substring(0, dot) : filename);
    }

    private String sanitizeId(String value) {
        return value.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
