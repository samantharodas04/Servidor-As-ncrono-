package protocol;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Cabecera común y versión única de los mensajes PAI. */
public final class PaiProtocol {
    public static final int VERSION = 1;
    public static final int HEADER_BYTES = 5;

    private static final byte MAGIC_P = 'P';
    private static final byte MAGIC_A = 'A';
    private static final byte MAGIC_I = 'I';

    private PaiProtocol() {
    }

    public static void putHeader(ByteBuffer output, PaiOpcode opcode) {
        output.put(MAGIC_P);
        output.put(MAGIC_A);
        output.put(MAGIC_I);
        output.put((byte) VERSION);
        output.put((byte) opcode.code());
    }

    public static void requireHeader(ByteBuffer input, PaiOpcode expected) throws IOException {
        if (input.remaining() < HEADER_BYTES) {
            throw new IOException("Mensaje PAI incompleto");
        }
        if (input.get() != MAGIC_P || input.get() != MAGIC_A || input.get() != MAGIC_I) {
            throw new IOException("Firma PAI invalida");
        }

        int version = Byte.toUnsignedInt(input.get());
        if (version != VERSION) {
            throw new IOException("Version PAI no soportada: " + version);
        }

        PaiOpcode actual = PaiOpcode.fromCode(Byte.toUnsignedInt(input.get()));
        if (actual != expected) {
            throw new IOException("Operacion PAI inesperada: " + actual);
        }
    }

    public static boolean isHeaderOnlyMessage(byte[] payload, PaiOpcode expected) {
        if (payload == null || payload.length != HEADER_BYTES) {
            return false;
        }
        try {
            requireHeader(ByteBuffer.wrap(payload), expected);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }
}
