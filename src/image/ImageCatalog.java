package image;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Descubre archivos originales y lee solamente sus encabezados. */
public final class ImageCatalog {
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "tif", "tiff", "psb", "webp", "avif"
    );

    private final Path originalsDirectory;
    private final ImageHeaderReader headerReader;

    public ImageCatalog(Path originalsDirectory) {
        this.originalsDirectory = originalsDirectory.toAbsolutePath().normalize();
        this.headerReader = new ImageHeaderReader();
    }

    public List<ImageSource> discover() throws IOException {
        Files.createDirectories(originalsDirectory);

        List<Path> files;
        try (var entries = Files.list(originalsDirectory)) {
            files = entries
                    .filter(Files::isRegularFile)
                    .filter(ImageCatalog::hasSupportedExtension)
                    .sorted(Comparator.comparing(
                            path -> path.getFileName().toString(),
                            String.CASE_INSENSITIVE_ORDER
                    ))
                    .toList();
        }

        List<ImageSource> sources = new ArrayList<>(files.size());
        Set<String> assignedIds = new HashSet<>();
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            String id = createId(fileName);
            ImageSource source;
            try {
                source = readSource(file, id);
            } catch (IOException e) {
                String reason = e.getMessage() == null ? e.toString()
                        : e.getMessage().lines().findFirst().orElse(e.toString());
                System.err.println("[IMAGE] Omitida " + fileName + ": " + reason);
                continue;
            }
            if (!assignedIds.add(id)) {
                throw new IOException("Dos imagenes producen el mismo identificador: " + id);
            }

            sources.add(source);
        }

        return List.copyOf(sources);
    }

    /** Busca un solo original sin abrir los encabezados de los demas archivos. */
    public ImageSource findById(String requestedId) throws IOException {
        Files.createDirectories(originalsDirectory);
        List<Path> matches;
        try (var entries = Files.list(originalsDirectory)) {
            matches = entries
                    .filter(Files::isRegularFile)
                    .filter(ImageCatalog::hasSupportedExtension)
                    .filter(path -> createId(path.getFileName().toString()).equals(requestedId))
                    .toList();
        }
        if (matches.isEmpty()) {
            throw new IOException("Imagen desconocida: " + requestedId);
        }
        if (matches.size() > 1) {
            throw new IOException("Dos imagenes producen el mismo identificador: "
                    + requestedId);
        }
        return readSource(matches.get(0), requestedId);
    }

    private ImageSource readSource(Path file, String id) throws IOException {
        ImageDimensions dimensions = headerReader.read(file);
        return new ImageSource(
                id,
                file.getFileName().toString(),
                file,
                Files.size(file),
                Files.getLastModifiedTime(file).toMillis(),
                dimensions.width(),
                dimensions.height()
        );
    }

    private static boolean hasSupportedExtension(Path path) {
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return false;
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return SUPPORTED_EXTENSIONS.contains(extension);
    }

    private static String createId(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String baseName = dot > 0 ? fileName.substring(0, dot) : fileName;
        String id = baseName
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        return id.isEmpty() ? "image" : id;
    }
}
