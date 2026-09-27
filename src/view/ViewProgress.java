package view;

import image.RenderedChunk;

import java.io.IOException;

/** Eventos de una VIEW: el servidor puede enviarlos sin esperar el resultado completo. */
public interface ViewProgress {
    default void onStart(ViewRequest request, ViewRegion region, int chunkCount)
            throws IOException {
    }

    default void onChunk(ViewRequest request, int index, RenderedChunk chunk)
            throws IOException {
    }
}
