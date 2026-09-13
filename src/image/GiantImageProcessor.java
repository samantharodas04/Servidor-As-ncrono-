package image;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Procesador para imágenes gigantes. Java NO carga el raster completo a RAM.
 * El servidor invoca ImageMagick como herramienta local de procesamiento y
 * recibe el resultado como una pirámide de tiles JPEG almacenada en disco.
 *
 * PSB se procesa usando la imagen compuesta [0], evitando cargar sus capas
 * individualmente. El procesamiento es de una sola vez y queda cacheado.
 */
public class GiantImageProcessor {
    private final Path processedDir;
    private final int tileSize;

    public GiantImageProcessor(Path processedDir, int tileSize) {
        this.processedDir = processedDir;
        this.tileSize = tileSize;
    }

    public boolean isImageMagickAvailable() {
        return findMagickMode() != null;
    }

    // Retorna "IM7" para el comando magick o "IM6" para convert/identify.
    private String findMagickMode() {
        if (commandWorks("magick", "-version")) return "IM7";
        if (commandWorks("convert", "-version") && commandWorks("identify", "-version")) return "IM6";
        return null;
    }

    private boolean commandWorks(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public ImageMetadata process(Path input, String id) throws IOException {
        String magickMode = findMagickMode();
        if (magickMode == null) {
            throw new IOException(
                    "La imagen gigante requiere ImageMagick. Verifique 'magick -version' (IM7) o 'convert -version' (IM6)."
            );
        }

        long sourceBytes = Files.size(input);
        long sourceModified = Files.getLastModifiedTime(input).toMillis();
        int[] dimensions = identify(input);
        int width = dimensions[0];
        int height = dimensions[1];
        int levels = calculateLevels(width, height);

        Path imageRoot = processedDir.resolve(id);
        Files.createDirectories(imageRoot);
        Path tempRoot = processedDir.resolve(".magick_tmp").resolve(id);
        Files.createDirectories(tempRoot);

        System.out.println("[GIANT] Archivo: " + input.getFileName());
        System.out.println("[GIANT] Tamaño en disco: " + humanBytes(sourceBytes));
        System.out.println("[GIANT] Dimensiones: " + width + "x" + height);
        System.out.println("[GIANT] Tiles: " + tileSize + "x" + tileSize + ", niveles=" + levels);
        System.out.println("[GIANT] La primera preparación puede tardar bastante; después se reutiliza el cache.");

        for (int level = 0; level < levels; level++) {
            double scale = Math.pow(2, level - (levels - 1));
            int levelWidth = Math.max(1, (int) Math.round(width * scale));
            int levelHeight = Math.max(1, (int) Math.round(height * scale));
            int cols = (int) Math.ceil(levelWidth / (double) tileSize);
            int rows = (int) Math.ceil(levelHeight / (double) tileSize);

            Path finalLevel = imageRoot.resolve("level" + level);
            Path doneMarker = finalLevel.resolve(".complete");
            if (Files.exists(doneMarker)) {
                System.out.printf(Locale.ROOT,
                        "[GIANT] Nivel %d/%d ya preparado (%dx%d).%n",
                        level + 1, levels, levelWidth, levelHeight);
                continue;
            }

            Path staging = imageRoot.resolve(".level" + level + "_staging");
            deleteTree(staging);
            Files.createDirectories(staging);

            System.out.printf(Locale.ROOT,
                    "[GIANT] Procesando nivel %d/%d: %dx%d -> %d x %d tiles%n",
                    level + 1, levels, levelWidth, levelHeight, cols, rows);

            List<String> command = new ArrayList<>();
            command.add("IM7".equals(magickMode) ? "magick" : "convert");
            command.add("-limit"); command.add("thread"); command.add("2");
            command.add("-limit"); command.add("memory"); command.add("768MiB");
            command.add("-limit"); command.add("map"); command.add("2GiB");
            command.add("-limit"); command.add("disk"); command.add("250GiB");
            command.add(input.toAbsolutePath() + "[0]");
            command.add("-resize"); command.add(levelWidth + "x" + levelHeight + "!");
            command.add("-background"); command.add("white");
            command.add("-alpha"); command.add("remove");
            command.add("-alpha"); command.add("off");
            command.add("-crop"); command.add(tileSize + "x" + tileSize);
            command.add("+repage");
            command.add("-strip");
            command.add("-quality"); command.add("82");
            command.add("+adjoin");
            command.add(staging.resolve("tile_%06d.jpg").toString());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.environment().put("MAGICK_TEMPORARY_PATH", tempRoot.toAbsolutePath().toString());
            pb.environment().put("MAGICK_THREAD_LIMIT", "2");

            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) System.out.println("[ImageMagick] " + line);
                }
            }

            int exit;
            try {
                exit = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IOException("Procesamiento interrumpido", e);
            }
            if (exit != 0) {
                throw new IOException("ImageMagick terminó con código " + exit + " al procesar nivel " + level);
            }

            renameSequentialTiles(staging, cols, rows);
            Files.writeString(staging.resolve(".complete"),
                    "width=" + levelWidth + "\nheight=" + levelHeight + "\n",
                    StandardCharsets.UTF_8);

            deleteTree(finalLevel);
            try {
                Files.move(staging, finalLevel, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staging, finalLevel);
            }
        }

        ImageMetadata meta = new ImageMetadata(
                id, width, height, tileSize, levels,
                input.getFileName().toString(), sourceBytes, sourceModified,
                "imagemagick-disk-tiles"
        );
        Files.writeString(imageRoot.resolve("metadata.json"), meta.toJson(), StandardCharsets.UTF_8);
        return meta;
    }

    private int[] identify(Path input) throws IOException {
        String mode = findMagickMode();
        List<String> command = new ArrayList<>();
        if ("IM7".equals(mode)) {
            command.add("magick");
            command.add("identify");
        } else {
            command.add("identify");
        }
        command.add("-ping");
        command.add("-format");
        command.add("%w %h");
        command.add(input.toAbsolutePath() + "[0]");
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output;
        try (InputStream in = p.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        try {
            if (p.waitFor() != 0) {
                throw new IOException("ImageMagick no pudo identificar la imagen: " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Identificación interrumpida", e);
        }

        Matcher m = Pattern.compile("(\\d+)\\s+(\\d+)").matcher(output);
        if (!m.find()) throw new IOException("No se pudieron obtener dimensiones: " + output);
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
    }

    private int calculateLevels(int width, int height) {
        int maxDim = Math.max(width, height);
        int levels = 1;
        int dim = maxDim;
        while (dim > tileSize) {
            dim = (int) Math.ceil(dim / 2.0);
            levels++;
        }
        return levels;
    }

    private void renameSequentialTiles(Path staging, int cols, int rows) throws IOException {
        List<Path> tiles;
        try (var stream = Files.list(staging)) {
            tiles = stream
                    .filter(p -> p.getFileName().toString().startsWith("tile_"))
                    .filter(p -> p.getFileName().toString().endsWith(".jpg"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }

        int expected = cols * rows;
        if (tiles.size() != expected) {
            throw new IOException("Cantidad de tiles inesperada. Esperados=" + expected + ", generados=" + tiles.size());
        }

        for (int i = 0; i < tiles.size(); i++) {
            int x = i % cols;
            int y = i / cols;
            Files.move(tiles.get(i), staging.resolve(x + "_" + y + ".jpg"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private String humanBytes(long bytes) {
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        while (value >= 1024 && i < units.length - 1) {
            value /= 1024;
            i++;
        }
        return String.format(Locale.ROOT, "%.2f %s", value, units[i]);
    }
}
