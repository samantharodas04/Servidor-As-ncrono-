"use strict";

// Opciones del visor. Guardar y recargar la pagina despues de cambiarlas.
// El servidor permite 2x y 4x para imagenes gigantes; este flag solo los oculta en UI.
// Las medidas de PAI/1 y el umbral de 50 000 px no se cambian aqui.
export const viewerConfig = Object.freeze({
    // true: mostrar niveles 2x y 4x en imagenes de 50 000 px o mas.
    enableExtraGiantZoom: true,
    // Memoria estimada de bitmaps decodificados, en MiB.
    bitmapCacheMiB: 12,
    // Decodificaciones simultaneas y limite de reintentos del WebSocket.
    decodeLanes: 2,
    maxReconnectAttempts: 5,
    // Espera exponencial entre reconexiones, en milisegundos.
    reconnectBaseDelayMs: 500,
    reconnectMaxDelayMs: 8000,
    // Espera tras el ultimo evento de la rueda antes de solicitar otra vista.
    wheelDebounceMs: 150
});

for (const [name, value] of Object.entries(viewerConfig)) {
    if (name === "enableExtraGiantZoom") {
        if (typeof value !== "boolean") throw new Error(`${name} debe ser booleano`);
    } else if (!Number.isSafeInteger(value) || value < 1) {
        throw new Error(`${name} debe ser un entero positivo`);
    }
}
