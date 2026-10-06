package image;

import java.io.IOException;
import java.nio.file.Path;

/** Ejecuta make prepare-image fuera de las solicitudes VIEW; conserva el original. */
public final class ImagePreparationTool {
    private ImagePreparationTool() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 2 || args[0].isBlank()
                || (args.length == 2 && !"--overview-only".equals(args[1]))) {
            throw new IllegalArgumentException(
                    "Uso: java -cp out image.ImagePreparationTool IMAGE_ID [--overview-only]"
            );
        }
        ImageSource selected = new ImageCatalog(
                Path.of("images", "originals")
        ).findById(args[0]);
        PreparedSourceStore store = new PreparedSourceStore(Path.of("images", "processed"));
        if (args.length == 2) {
            store.prepareOverview(selected);
        } else {
            store.prepare(selected);
        }
    }
}
