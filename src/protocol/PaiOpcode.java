package protocol;

import java.io.IOException;

/** Operaciones binarias definidas por PAI/1. */
public enum PaiOpcode {
    VIEW(1),
    LIST_IMAGES(2),
    IMAGE_LIST(3),
    VIEW_START(4),
    CHUNK(5),
    VIEW_END(6),
    CHUNK_PNG(7),
    VIEW_ERROR(8),
    CHUNK_ACK(9),
    VIEW_CACHED(10),
    CHUNK_REF(11);

    private final int code;

    PaiOpcode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static PaiOpcode fromCode(int code) throws IOException {
        for (PaiOpcode opcode : values()) {
            if (opcode.code == code) {
                return opcode;
            }
        }
        throw new IOException("Operacion PAI desconocida: " + code);
    }
}
