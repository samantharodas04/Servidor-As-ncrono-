package image;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/** Lee solamente las dimensiones declaradas en el encabezado de una imagen. */
public final class ImageHeaderReader {
    private static final long VIPS_TIMEOUT_SECONDS = 30;

    public ImageDimensions read(Path image) throws IOException {
        IOException imageIoFailure = null;
        try {
            ImageDimensions dimensions = readWithImageIo(image);
            if (dimensions != null) {
                return dimensions;
            }
        } catch (IOException e) {
            imageIoFailure = e;
        }

        try {
            return new ImageDimensions(
                    readVipsField(image, "width"),
                    readVipsField(image, "height")
            );
        } catch (IOException vipsFailure) {
            if (imageIoFailure != null) {
                vipsFailure.addSuppressed(imageIoFailure);
            }
            throw vipsFailure;
        }
    }

    private ImageDimensions readWithImageIo(Path image) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(image.toFile())) {
            if (input == null) {
                return null;
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return new ImageDimensions(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        }
    }

    private int readVipsField(Path image, String field) throws IOException {
        Process process = new ProcessBuilder(
                "vipsheader", "-f", field, image.toAbsolutePath().toString()
        ).redirectErrorStream(true).start();

        final boolean completed;
        try {
            completed = process.waitFor(VIPS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Se interrumpio la lectura del encabezado", e);
        }

        if (!completed) {
            process.destroyForcibly();
            throw new IOException("vipsheader excedio el tiempo limite para " + image.getFileName());
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) {
            throw new IOException("vipsheader no pudo leer " + image.getFileName() + ": " + output);
        }

        try {
            int value = Integer.parseInt(output);
            if (value <= 0) {
                throw new NumberFormatException("dimension no positiva");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IOException("Valor invalido de " + field + " para " + image.getFileName(), e);
        }
    }
}
