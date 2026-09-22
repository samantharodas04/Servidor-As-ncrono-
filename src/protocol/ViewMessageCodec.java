package protocol;

import view.ViewRequest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Codifica y valida el primer mensaje binario del protocolo: VIEW. */
public final class ViewMessageCodec {
    public static final int VERSION = PaiProtocol.VERSION;
    public static final int VIEW_OPCODE = PaiOpcode.VIEW.code();
    public static final int FIXED_HEADER_BYTES = 35;
    public static final int MAX_IMAGE_ID_BYTES = 128;

    private ViewMessageCodec() {
    }

    /**
     * Formato de red, en big-endian:
     * PAI(3), version(1), opcode(1), generationId(8), cinco enteros(20),
     * longitud del ID(2) e ID UTF-8(variable).
     */
    public static byte[] encode(ViewRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        byte[] imageId = encodeImageId(request.imageId());

        ByteBuffer output = ByteBuffer
                .allocate(FIXED_HEADER_BYTES + imageId.length)
                .order(ByteOrder.BIG_ENDIAN);
        PaiProtocol.putHeader(output, PaiOpcode.VIEW);
        output.putLong(request.generationId());
        output.putInt(request.zoomIndex());
        output.putInt(request.centerX());
        output.putInt(request.centerY());
        output.putInt(request.viewportWidth());
        output.putInt(request.viewportHeight());
        output.putShort((short) imageId.length);
        output.put(imageId);
        return output.array();
    }

    /** Convierte un frame binario VIEW en el ViewRequest usado por el backend. */
    public static ViewRequest decode(byte[] frame, int serverChunkSize) throws IOException {
        Objects.requireNonNull(frame, "frame");
        if (frame.length < FIXED_HEADER_BYTES) {
            throw new IOException("VIEW incompleto: faltan bytes de cabecera");
        }
        if (serverChunkSize <= 0) {
            throw new IllegalArgumentException("serverChunkSize debe ser positivo");
        }

        ByteBuffer input = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        PaiProtocol.requireHeader(input, PaiOpcode.VIEW);

        long generationId = input.getLong();
        int zoomIndex = input.getInt();
        int centerX = input.getInt();
        int centerY = input.getInt();
        int viewportWidth = input.getInt();
        int viewportHeight = input.getInt();
        int imageIdLength = Short.toUnsignedInt(input.getShort());

        if (imageIdLength < 1 || imageIdLength > MAX_IMAGE_ID_BYTES) {
            throw new IOException("Longitud de imageId invalida: " + imageIdLength);
        }
        if (input.remaining() != imageIdLength) {
            throw new IOException("La longitud declarada de imageId no coincide con el frame");
        }

        byte[] imageIdBytes = new byte[imageIdLength];
        input.get(imageIdBytes);
        String imageId = decodeImageId(imageIdBytes);

        try {
            return new ViewRequest(
                    generationId,
                    imageId,
                    zoomIndex,
                    centerX,
                    centerY,
                    viewportWidth,
                    viewportHeight,
                    serverChunkSize
            );
        } catch (IllegalArgumentException e) {
            throw new IOException("VIEW contiene valores invalidos: " + e.getMessage(), e);
        }
    }

    private static byte[] encodeImageId(String imageId) throws IOException {
        if (!isValidImageId(imageId)) {
            throw new IOException("imageId contiene caracteres no permitidos");
        }
        byte[] encoded = imageId.getBytes(StandardCharsets.UTF_8);
        if (encoded.length < 1 || encoded.length > MAX_IMAGE_ID_BYTES) {
            throw new IOException("imageId excede el limite de bytes");
        }
        return encoded;
    }

    private static String decodeImageId(byte[] encoded) throws IOException {
        final String imageId;
        try {
            imageId = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoded))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IOException("imageId no es UTF-8 valido", e);
        }
        if (!isValidImageId(imageId)) {
            throw new IOException("imageId contiene caracteres no permitidos");
        }
        return imageId;
    }

    private static boolean isValidImageId(String imageId) {
        return imageId != null && imageId.matches("[a-z0-9_-]+");
    }
}
