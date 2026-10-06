package protocol;

import image.ImageSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Mensajes PAI para solicitar y devolver el catálogo ligero de imágenes. */
public final class ImageCatalogCodec {
    public static final int LIST_IMAGES_OPCODE = PaiOpcode.LIST_IMAGES.code();
    public static final int IMAGE_LIST_OPCODE = PaiOpcode.IMAGE_LIST.code();
    public static final int MAX_IMAGES = 1_024;
    public static final int MAX_NAME_BYTES = 1_024;

    private static final int REQUEST_BYTES = PaiProtocol.HEADER_BYTES;
    private static final int RESPONSE_HEADER_BYTES = 7;

    private ImageCatalogCodec() {
    }

    /** LIST_IMAGES no necesita campos adicionales. */
    public static boolean isListImagesRequest(byte[] payload) {
        return PaiProtocol.isHeaderOnlyMessage(payload, PaiOpcode.LIST_IMAGES);
    }

    /**
     * Formato big-endian:
     * PAI(3), versión(1), opcode(1), cantidad(2) y entradas variables.
     * Cada entrada contiene ID UTF-8, nombre UTF-8, ancho(4), alto(4) y bytes(8).
     */
    public static byte[] encodeImageList(List<ImageSource> images) throws IOException {
        Objects.requireNonNull(images, "images");
        if (images.size() > MAX_IMAGES) {
            throw new IOException("El catálogo excede el máximo de imágenes");
        }

        List<EncodedImage> encoded = images.stream()
                .map(ImageCatalogCodec::encodeImage)
                .toList();
        long totalBytes = RESPONSE_HEADER_BYTES;
        for (EncodedImage image : encoded) {
            totalBytes += 2L + image.id().length;
            totalBytes += 2L + image.name().length;
            totalBytes += Integer.BYTES * 2L + Long.BYTES;
        }
        if (totalBytes > Integer.MAX_VALUE) {
            throw new IOException("El catálogo es demasiado grande");
        }

        ByteBuffer output = ByteBuffer
                .allocate((int) totalBytes)
                .order(ByteOrder.BIG_ENDIAN);
        PaiProtocol.putHeader(output, PaiOpcode.IMAGE_LIST);
        output.putShort((short) encoded.size());
        for (int index = 0; index < encoded.size(); index++) {
            ImageSource source = images.get(index);
            EncodedImage image = encoded.get(index);
            putText(output, image.id());
            putText(output, image.name());
            output.putInt(source.width());
            output.putInt(source.height());
            output.putLong(source.sizeBytes());
        }
        return output.array();
    }

    private static EncodedImage encodeImage(ImageSource image) {
        Objects.requireNonNull(image, "image");
        byte[] id = image.id().getBytes(StandardCharsets.UTF_8);
        byte[] name = image.fileName().getBytes(StandardCharsets.UTF_8);
        if (id.length < 1 || id.length > ViewMessageCodec.MAX_IMAGE_ID_BYTES) {
            throw new IllegalArgumentException("Longitud de imageId inválida");
        }
        if (name.length < 1 || name.length > MAX_NAME_BYTES) {
            throw new IllegalArgumentException("Longitud de nombre inválida");
        }
        return new EncodedImage(id, name);
    }

    private static void putText(ByteBuffer output, byte[] text) {
        output.putShort((short) text.length);
        output.put(text);
    }

    private record EncodedImage(byte[] id, byte[] name) {
    }
}
