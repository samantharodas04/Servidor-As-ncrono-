package image;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Lee el PSB RGB sin comprimir: primero todo R, despues G y finalmente B. */
public final class RawPsbSource {
    private final Path path;
    private final int width;
    private final int height;
    private final long pixelOffset;
    private final long planeBytes;

    private RawPsbSource(Path path, int width, int height, long pixelOffset) {
        this.path = path;
        this.width = width;
        this.height = height;
        this.pixelOffset = pixelOffset;
        this.planeBytes = (long) width * height;
    }

    public static RawPsbSource open(Path path) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(path.toFile(), "r")) {
            if (input.readInt() != 0x38425053 || input.readUnsignedShort() != 2) {
                throw new IOException("Encabezado PSB invalido o version no compatible");
            }
            input.skipBytes(6);
            int channels = input.readUnsignedShort();
            int height = input.readInt();
            int width = input.readInt();
            int depth = input.readUnsignedShort();
            int colorMode = input.readUnsignedShort();
            if (channels != 3 || width <= 0 || height <= 0 || depth != 8 || colorMode != 3) {
                throw new IOException("PSB requiere compuesto RGB de 8 bits y tres canales");
            }
            skipSection(input, Integer.toUnsignedLong(input.readInt()));
            skipSection(input, Integer.toUnsignedLong(input.readInt()));
            long layerBytes = input.readLong();
            if (layerBytes < 0) {
                throw new IOException("Longitud de capas PSB invalida");
            }
            skipSection(input, layerBytes);
            if (input.readUnsignedShort() != 0) {
                throw new IOException("Compresion PSB no compatible; se requiere RGB sin comprimir");
            }
            long pixelOffset = input.getFilePointer();
            long pixelBytes = Math.multiplyExact((long) width * height, channels);
            if (pixelBytes > input.length() - pixelOffset) {
                throw new IOException("Datos RGB incompletos en el PSB");
            }
            return new RawPsbSource(path, width, height, pixelOffset);
        } catch (ArithmeticException e) {
            throw new IOException("Dimensiones PSB fuera del limite", e);
        }
    }

    private static void skipSection(RandomAccessFile input, long bytes) throws IOException {
        long next = input.getFilePointer() + bytes;
        if (next < input.getFilePointer() || next > input.length()) {
            throw new IOException("Seccion PSB fuera del archivo");
        }
        input.seek(next);
    }

    public ImageDimensions dimensions() {
        return new ImageDimensions(width, height);
    }

    /** Intercala los tres planos en disco para una conversion BigTIFF explicita. */
    public void writeFullPpm(Path target) throws IOException {
        byte[][] channels = new byte[3][width];
        byte[] row = new byte[Math.multiplyExact(width, 3)];
        try (RandomAccessFile input = new RandomAccessFile(path.toFile(), "r");
             OutputStream output = new BufferedOutputStream(Files.newOutputStream(target),
                     1024 * 1024)) {
            output.write(("P6\n" + width + " " + height + "\n255\n")
                    .getBytes(StandardCharsets.US_ASCII));
            for (int y = 0; y < height; y++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Conversion PSB cancelada");
                }
                readRow(input, y, 0, channels);
                for (int x = 0; x < width; x++) {
                    int pixel = x * 3;
                    row[pixel] = channels[0][x];
                    row[pixel + 1] = channels[1][x];
                    row[pixel + 2] = channels[2][x];
                }
                output.write(row);
            }
        }
    }

    /** Lee solo las filas necesarias y produce una vista RGB pequena para VIEW. */
    public void writePpm(Path target, int outputWidth, int outputHeight,
                         double originX, double originY, double scaleX, double scaleY)
            throws IOException {
        if (outputWidth <= 0 || outputHeight <= 0 || scaleX <= 0 || scaleY <= 0) {
            throw new IllegalArgumentException("Vista PSB invalida");
        }
        int firstX = clamp((int) Math.floor(originX), width - 1);
        int lastX = clamp((int) Math.ceil(originX + (outputWidth - 1) / scaleX) + 1,
                width - 1);
        int span = lastX - firstX + 1;
        byte[][] upper = new byte[3][span];
        byte[][] lower = new byte[3][span];
        byte[] outputRow = new byte[Math.multiplyExact(outputWidth, 3)];
        boolean interpolate = scaleX < 1.0 || scaleY < 1.0;

        try (RandomAccessFile input = new RandomAccessFile(path.toFile(), "r");
             OutputStream output = new BufferedOutputStream(Files.newOutputStream(target))) {
            output.write(("P6\n" + outputWidth + " " + outputHeight + "\n255\n")
                    .getBytes(StandardCharsets.US_ASCII));
            int loadedUpper = -1;
            int loadedLower = -1;
            for (int y = 0; y < outputHeight; y++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Lectura PSB cancelada");
                }
                double sy = Math.min(height - 1, originY + y / scaleY);
                int y0 = clamp((int) Math.floor(sy), height - 1);
                int y1 = Math.min(height - 1, y0 + 1);
                if (loadedLower == y0) {
                    byte[][] swap = upper;
                    upper = lower;
                    lower = swap;
                    loadedUpper = y0;
                    loadedLower = -1;
                }
                if (loadedUpper != y0) {
                    readRow(input, y0, firstX, upper);
                    loadedUpper = y0;
                }
                if (interpolate && y1 != y0 && loadedLower != y1) {
                    readRow(input, y1, firstX, lower);
                    loadedLower = y1;
                }
                double fy = interpolate ? sy - y0 : 0;
                for (int x = 0; x < outputWidth; x++) {
                    double sx = Math.min(width - 1, originX + x / scaleX);
                    int x0 = clamp((int) Math.floor(sx), width - 1) - firstX;
                    int x1 = Math.min(span - 1, x0 + 1);
                    double fx = interpolate ? sx - Math.floor(sx) : 0;
                    for (int channel = 0; channel < 3; channel++) {
                        int a = upper[channel][x0] & 0xff;
                        int b = upper[channel][x1] & 0xff;
                        double top = a + (b - a) * fx;
                        double bottom = top;
                        if (fy > 0) {
                            a = lower[channel][x0] & 0xff;
                            b = lower[channel][x1] & 0xff;
                            bottom = a + (b - a) * fx;
                        }
                        outputRow[x * 3 + channel] = (byte) Math.round(top + (bottom - top) * fy);
                    }
                }
                output.write(outputRow);
            }
        }
    }

    private void readRow(RandomAccessFile input, int y, int x, byte[][] channels)
            throws IOException {
        for (int channel = 0; channel < 3; channel++) {
            input.seek(pixelOffset + channel * planeBytes + (long) y * width + x);
            input.readFully(channels[channel]);
        }
    }

    private static int clamp(int value, int maximum) {
        return Math.max(0, Math.min(maximum, value));
    }
}
