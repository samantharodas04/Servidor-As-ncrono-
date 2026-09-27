package protocol;

import image.RenderedChunk;
import view.PlannedChunk;
import view.ViewRegion;
import view.ViewRequest;
import view.ViewResult;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
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
        return encodeViewStart(result.request(), result.region(), result.chunks().size());
    }

    public static byte[] encodeViewStart(
            ViewRequest request, ViewRegion region, int chunkCount
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(region, "region");
        if (chunkCount < 1) {
            throw new IllegalArgumentException("VIEW necesita al menos un chunk");
        }
        ByteBuffer output = buffer(VIEW_START_BYTES);
        PaiProtocol.putHeader(output, PaiOpcode.VIEW_START);
        output.putLong(request.generationId());
        output.putInt(request.viewportWidth());
        output.putInt(request.viewportHeight());
        output.putInt(request.zoomIndex());
        output.putInt(region.x());
        output.putInt(region.y());
        output.putInt(region.width());
        output.putInt(region.height());
        output.putInt(chunkCount);
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
        return encodeChunk(result.request(), chunkIndex, result.chunks().get(chunkIndex));
    }

    public static byte[] encodeChunk(
            ViewRequest request, int chunkIndex, RenderedChunk rendered
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(rendered, "rendered");
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("Indice de chunk fuera de rango");
        }
        PlannedChunk chunk = rendered.chunk();
        byte[] bytes = rendered.bytes();
        ByteBuffer output = buffer(CHUNK_METADATA_BYTES + bytes.length);
        PaiProtocol.putHeader(output, rendered.png() ? PaiOpcode.CHUNK_PNG : PaiOpcode.CHUNK);
        output.putLong(request.generationId());
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

    /** Informa una VIEW interrumpida por error sin cerrar el WebSocket. */
    public static byte[] encodeViewError(ViewRequest request, String message) {
        Objects.requireNonNull(request, "request");
        byte[] description = Objects.requireNonNull(message, "message")
                .getBytes(StandardCharsets.UTF_8);
        if (description.length > 240) {
            throw new IllegalArgumentException("Mensaje VIEW_ERROR demasiado largo");
        }
        ByteBuffer output = buffer(PaiProtocol.HEADER_BYTES + Long.BYTES
                + Short.BYTES + description.length);
        PaiProtocol.putHeader(output, PaiOpcode.VIEW_ERROR);
        output.putLong(request.generationId());
        output.putShort((short) description.length);
        output.put(description);
        return output.array();
    }

    private static ByteBuffer buffer(int bytes) {
        return ByteBuffer.allocate(bytes).order(ByteOrder.BIG_ENDIAN);
    }
}
