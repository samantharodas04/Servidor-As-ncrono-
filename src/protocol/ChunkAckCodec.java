package protocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** CHUNK_ACK: cabecera PAI/1, generación (8 bytes) e índice (4 bytes). */
public final class ChunkAckCodec {
    public static final int MESSAGE_BYTES = PaiProtocol.HEADER_BYTES + Long.BYTES + Integer.BYTES;

    private ChunkAckCodec() {
    }

    public static Ack decode(byte[] payload) throws IOException {
        if (payload.length != MESSAGE_BYTES) {
            throw new IOException("CHUNK_ACK tiene una longitud invalida");
        }
        ByteBuffer input = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        PaiProtocol.requireHeader(input, PaiOpcode.CHUNK_ACK);
        long generationId = input.getLong();
        int chunkIndex = input.getInt();
        if (generationId < 1 || chunkIndex < 0) {
            throw new IOException("CHUNK_ACK contiene valores invalidos");
        }
        return new Ack(generationId, chunkIndex);
    }

    public record Ack(long generationId, int chunkIndex) {
    }
}
