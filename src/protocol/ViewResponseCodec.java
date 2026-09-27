package protocol;

import image.RenderedChunk;
import view.PlannedChunk;
import view.ViewResult;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Codifica START, chunks JPEG/PNG y END; el opcode indica el formato del chunk. */
public final class ViewResponseCodec {
    public static final int VIEW_START_BYTES = PaiProtocol.HEADER_BYTES
            + Long.BYTES + (Integer.BYTES * 8);
    public static final int CHUNK_METADATA_BYTES = PaiProtocol.HEADER_BYTES
            + Long.BYTES + (Integer.BYTES * 8);
    public static final int VIEW_END_BYTES = PaiProtocol.HEADER_BYTES
            + Long.BYTES + Integer.BYTES + Long.BYTES;

    private ViewResponseCodec() {
    }

    /**
     * generationId(8), viewport(8), zoomIndex(4), region original(16)
     * y cantidad de chunks(4).
     */
    public static byte[] encodeViewStart(ViewResult result) {
        Objects.requireNonNull(result, "result");
        ByteBuffer output = buffer(VIEW_START_BYTES);
        PaiProtocol.putHeader(output, PaiOpcode.VIEW_START);
        output.putLong(result.request().generationId());
        output.putInt(result.request().viewportWidth());
        output.putInt(result.request().viewportHeight());
        output.putInt(result.request().zoomIndex());
        output.putInt(result.region().x());
        output.putInt(result.region().y());
        output.putInt(result.region().width());
        output.putInt(result.region().height());
        output.putInt(result.chunks().size());
        return output.array();
    }

    /**
     * generationId(8), indice(4), columna/fila(8), posicion canvas(8),
     * dimensiones de salida(8), longitud(4) e imagen JPEG o PNG(variable).
     */
    public static byte[] encodeChunk(ViewResult result, int chunkIndex) {
        Objects.requireNonNull(result, "result");
        if (chunkIndex < 0 || chunkIndex >= result.chunks().size()) {
            throw new IllegalArgumentException("Indice de chunk fuera de rango");
        }

        RenderedChunk rendered = result.chunks().get(chunkIndex);
        PlannedChunk chunk = rendered.chunk();
        byte[] bytes = rendered.bytes();
        ByteBuffer output = buffer(CHUNK_METADATA_BYTES + bytes.length);
        PaiProtocol.putHeader(output, rendered.png() ? PaiOpcode.CHUNK_PNG : PaiOpcode.CHUNK);
        output.putLong(result.request().generationId());
        output.putInt(chunkIndex);
        output.putInt(chunk.column());
        output.putInt(chunk.row());
        output.putInt(chunk.canvasX());
        output.putInt(chunk.canvasY());
        output.putInt(chunk.outputWidth());
        output.putInt(chunk.outputHeight());
        output.putInt(bytes.length);
        output.put(bytes);
        return output.array();
    }

    /** generationId(8), cantidad de chunks(4) y bytes de imagen totales(8). */
    public static byte[] encodeViewEnd(ViewResult result) {
        Objects.requireNonNull(result, "result");
        ByteBuffer output = buffer(VIEW_END_BYTES);
        PaiProtocol.putHeader(output, PaiOpcode.VIEW_END);
        output.putLong(result.request().generationId());
        output.putInt(result.chunks().size());
        output.putLong(result.totalChunkBytes());
        return output.array();
    }

    private static ByteBuffer buffer(int bytes) {
        return ByteBuffer.allocate(bytes).order(ByteOrder.BIG_ENDIAN);
    }
}
