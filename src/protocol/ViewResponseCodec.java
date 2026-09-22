package protocol;

import image.RenderedChunk;
import view.PlannedChunk;
import view.ViewResult;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Codifica la secuencia VIEW_START, CHUNK y VIEW_END enviada al navegador. */
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
     * dimensiones de salida(8), longitud JPEG(4) y JPEG(variable).
     */
    public static byte[] encodeChunk(ViewResult result, int chunkIndex) {
        Objects.requireNonNull(result, "result");
        if (chunkIndex < 0 || chunkIndex >= result.chunks().size()) {
            throw new IllegalArgumentException("Indice de chunk fuera de rango");
        }

        RenderedChunk rendered = result.chunks().get(chunkIndex);
        PlannedChunk chunk = rendered.chunk();
        byte[] jpeg = rendered.jpeg();
        ByteBuffer output = buffer(CHUNK_METADATA_BYTES + jpeg.length);
        PaiProtocol.putHeader(output, PaiOpcode.CHUNK);
        output.putLong(result.request().generationId());
        output.putInt(chunkIndex);
        output.putInt(chunk.column());
        output.putInt(chunk.row());
        output.putInt(chunk.canvasX());
        output.putInt(chunk.canvasY());
        output.putInt(chunk.outputWidth());
        output.putInt(chunk.outputHeight());
        output.putInt(jpeg.length);
        output.put(jpeg);
        return output.array();
    }

    /** generationId(8), cantidad de chunks(4) y bytes JPEG totales(8). */
    public static byte[] encodeViewEnd(ViewResult result) {
        Objects.requireNonNull(result, "result");
        ByteBuffer output = buffer(VIEW_END_BYTES);
        PaiProtocol.putHeader(output, PaiOpcode.VIEW_END);
        output.putLong(result.request().generationId());
        output.putInt(result.chunks().size());
        output.putLong(result.totalJpegBytes());
        return output.array();
    }

    private static ByteBuffer buffer(int bytes) {
        return ByteBuffer.allocate(bytes).order(ByteOrder.BIG_ENDIAN);
    }
}
