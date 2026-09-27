"use strict";

import {PaiOpcode, encodeListImages, encodeView, readPaiOpcode,
    decodeImageList, decodeViewStart, decodeChunk, decodeViewEnd} from "./protocol.js";
import {zoomScales as calculateZoomScales, initialView as createInitialView,
    clampVisibleCenter} from "./navigation.js";
import {BitmapCache} from "./bitmap-cache.js";
import {createMinimap} from "./minimap.js";

// Cambiar a false y recargar la página para detener el zoom gigante en 1:1.
const ENABLE_EXTRA_GIANT_ZOOM = true;

// 8. El visor conserva el catálogo, la solicitud vigente y los bitmaps reutilizables.
const statusElement = document.getElementById("connectionStatus");
const viewStatusElement = document.getElementById("viewStatus");
const sendValidButton = document.getElementById("sendValidView");
const sendInvalidButton = document.getElementById("sendInvalidView");
const zoomOutButton = document.getElementById("zoomOut");
const zoomInButton = document.getElementById("zoomIn");
const zoomStatusElement = document.getElementById("zoomStatus");
const imageSelect = document.getElementById("imageSelect");
const imageInfoElement = document.getElementById("imageInfo");
const catalogStatusElement = document.getElementById("catalogStatus");
const cacheStatusElement = document.getElementById("cacheStatus");
const positionStatusElement = document.getElementById("positionStatus");
const imageCanvas = document.getElementById("imageCanvas");
const canvasContext = imageCanvas.getContext("2d");
const overviewPanel = document.getElementById("overviewPanel");
const overviewFrame = document.getElementById("overviewFrame");
const overviewCanvas = document.getElementById("overviewCanvas");
const overviewMarker = document.getElementById("overviewMarker");
const bitmapCache = new BitmapCache(12 * 1024 * 1024);

const scheme = window.location.protocol === "https:" ? "wss" : "ws";
const socket = new WebSocket(`${scheme}://${window.location.host}/pai`);
socket.binaryType = "arraybuffer";

const catalog = new Map();
let generationId = 1n;
let latestRequestedGenerationId = 0n;
let activeView = null;
let currentRequest = null;
let dragStart = null;
let wheelTimer = null;

function updateNavigation() {
    const image = catalog.get(imageSelect.value);
    const levels = image ? zoomScales(image) : [];
    const zoomIndex = currentRequest?.imageId === image?.id
        ? currentRequest.zoomIndex : 0;
    const ready = socket.readyState === WebSocket.OPEN && currentRequest !== null;
    zoomOutButton.disabled = !ready || zoomIndex === 0;
    zoomInButton.disabled = !ready || zoomIndex >= levels.length - 1;
    zoomStatusElement.textContent = image
        ? `Zoom ${zoomIndex + 1}/${levels.length}` : "Zoom: sin imagen";
    sendValidButton.textContent = currentRequest ? "Reiniciar vista" : "Mostrar imagen";
}

function requestView(view) {
    // Cada acción de navegación invalida la generación anterior.
    if (!requireOpenSocket()) return;
    const request = {...view, generationId: generationId++};
    currentRequest = request;
    latestRequestedGenerationId = request.generationId;
    activeView = null;
    socket.send(encodeView(request));
    updateNavigation();
    updatePosition();
    viewStatusElement.textContent =
        `VIEW ${request.generationId} enviado para ${request.imageId}`;
}

function changeZoom(direction) {
    const image = catalog.get(imageSelect.value);
    if (!image || !currentRequest) return;
    const nextIndex = Math.max(0, Math.min(zoomScales(image).length - 1,
        currentRequest.zoomIndex + direction));
    if (nextIndex === currentRequest.zoomIndex) return;
    requestView({...currentRequest, zoomIndex: nextIndex});
}

function moveView(deltaCanvasX, deltaCanvasY) {
    const image = catalog.get(imageSelect.value);
    if (!image || !currentRequest || currentRequest.zoomIndex === 0) return;
    const scale = zoomScales(image)[currentRequest.zoomIndex];
    const scaleX = Math.max(1, Math.round(image.width * scale)) / image.width;
    const scaleY = Math.max(1, Math.round(image.height * scale)) / image.height;
    const centerX = clampVisibleCenter(currentRequest.centerX - deltaCanvasX / scaleX,
        image.width, imageCanvas.width / scaleX);
    const centerY = clampVisibleCenter(currentRequest.centerY - deltaCanvasY / scaleY,
        image.height, imageCanvas.height / scaleY);
    if (centerX !== currentRequest.centerX || centerY !== currentRequest.centerY) {
        requestView({...currentRequest, centerX, centerY});
    }
}

// El controlador de miniatura lee el estado actual, sin duplicar solicitudes ni catálogo.
const minimap = createMinimap({
    panel: overviewPanel,
    frame: overviewFrame,
    canvas: overviewCanvas,
    marker: overviewMarker,
    imageCanvas,
    getImage: (id) => catalog.get(id),
    getRequest: () => currentRequest,
    getActiveView: () => activeView,
    socketReady: () => socket.readyState === WebSocket.OPEN,
    zoomScales,
    requestView,
    moveView
});

function zoomScales(image) {
    return calculateZoomScales(image, imageCanvas.width, imageCanvas.height,
        ENABLE_EXTRA_GIANT_ZOOM);
}

function initialView(image) {
    return createInitialView(image, imageCanvas.width, imageCanvas.height);
}

function formatBytes(bytes) {
    const units = ["B", "KiB", "MiB", "GiB"];
    let value = bytes;
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
        value /= 1024;
        unit++;
    }
    return value.toFixed(unit === 0 ? 0 : 1) + " " + units[unit];
}

function showSelectedImage() {
    const image = catalog.get(imageSelect.value);
    imageInfoElement.textContent = image
        ? image.width + " × " + image.height + " píxeles | " + formatBytes(image.sizeBytes)
        : "Ninguna imagen seleccionada";
    updateNavigation();
    updatePosition();
}

function updatePosition() {
    positionStatusElement.textContent = currentRequest
        ? `Centro ${currentRequest.centerX.toLocaleString("es-GT")}, `
            + `${currentRequest.centerY.toLocaleString("es-GT")}`
        : "Centro sin seleccionar";
}

function updateCacheStatus() {
    cacheStatusElement.textContent = `${bitmapCache.size} piezas · `
        + `${formatBytes(bitmapCache.currentBytes)} / `
        + `${formatBytes(bitmapCache.maximumBytes)} · `
        + `${bitmapCache.hits} reutilizadas`;
}

function showCatalog(images) {
    minimap.clear();
    bitmapCache.clear();
    updateCacheStatus();
    catalog.clear();
    imageSelect.replaceChildren();
    for (const image of images) {
        catalog.set(image.id, image);
        const option = document.createElement("option");
        option.value = image.id;
        option.textContent = image.name;
        imageSelect.append(option);
    }

    const hasImages = images.length > 0;
    imageSelect.disabled = !hasImages;
    sendValidButton.disabled = !hasImages;
    sendInvalidButton.disabled = !hasImages;
    catalogStatusElement.textContent = images.length + " imagen(es) disponible(s)";
    showSelectedImage();
}

function requireOpenSocket() {
    if (socket.readyState !== WebSocket.OPEN) {
        viewStatusElement.textContent = "El WebSocket no está conectado";
        return false;
    }
    return true;
}

function receiveViewStart(buffer) {
    // 9. START abre la vista; los CHUNK se dibujan en dos carriles de decodificación.
    const message = decodeViewStart(buffer);
    if (message.generationId !== latestRequestedGenerationId) return;

    imageCanvas.width = message.viewportWidth;
    imageCanvas.height = message.viewportHeight;
    activeView = {
        ...message,
        imageId: currentRequest.imageId,
        sourceSizeBytes: catalog.get(currentRequest.imageId)?.sizeBytes,
        receivedIndexes: new Set(),
        receivedBytes: 0n,
        drawLanes: [Promise.resolve(), Promise.resolve()],
        nextDrawLane: 0,
        drawError: null
    };
    minimap.updateMarker(activeView);
    viewStatusElement.textContent =
        `VIEW ${message.generationId}: esperando ${message.chunkCount} chunks`;
}

async function drawChunk(view, message) {
    if (activeView !== view) return;
    const key = JSON.stringify([
        view.imageId, view.sourceSizeBytes, view.zoomIndex,
        view.viewportWidth, view.viewportHeight,
        message.column, message.row, message.width, message.height
    ]);
    const cached = bitmapCache.get(key);
    if (cached) {
        canvasContext.drawImage(cached, message.canvasX, message.canvasY);
        updateCacheStatus();
        return;
    }

    const started = performance.now();
    const bitmap = await createImageBitmap(new Blob([message.jpeg], {type: "image/jpeg"}));
    let retained = false;
    try {
        if (bitmap.width !== message.width || bitmap.height !== message.height) {
            throw new Error("El tamaño JPEG no coincide con CHUNK");
        }
        if (activeView === view) {
            canvasContext.drawImage(bitmap, message.canvasX, message.canvasY);
            retained = bitmapCache.put(key, bitmap, performance.now() - started);
            updateCacheStatus();
        }
    } finally {
        if (!retained) bitmap.close();
    }
}

function receiveChunk(buffer) {
    const message = decodeChunk(buffer);
    if (!activeView || message.generationId !== activeView.generationId) return;
    if (message.index >= activeView.chunkCount
            || activeView.receivedIndexes.has(message.index)) {
        throw new Error("CHUNK duplicado o fuera de rango");
    }

    activeView.receivedIndexes.add(message.index);
    activeView.receivedBytes += BigInt(message.jpegBytes);
    viewStatusElement.textContent =
        `VIEW ${message.generationId}: ${activeView.receivedIndexes.size}/${activeView.chunkCount} chunks`;

    const view = activeView;
    const lane = view.nextDrawLane++ % view.drawLanes.length;
    view.drawLanes[lane] = view.drawLanes[lane].then(() => {
        if (activeView !== view || view.drawError) return;
        return drawChunk(view, message);
    }).catch((error) => {
        if (activeView === view) {
            view.drawError = error;
            viewStatusElement.textContent = "No se pudo dibujar un CHUNK: " + error.message;
        }
    });
}

async function receiveViewEnd(buffer) {
    // 10. END confirma cantidad y bytes antes de declarar completa la vista.
    const message = decodeViewEnd(buffer);
    if (!activeView || message.generationId !== activeView.generationId) return;
    const view = activeView;
    if (message.chunkCount !== view.chunkCount
            || view.receivedIndexes.size !== view.chunkCount
            || message.totalJpegBytes !== view.receivedBytes) {
        throw new Error("VIEW_END no coincide con los chunks recibidos");
    }

    await Promise.all(view.drawLanes);
    if (activeView !== view) return;
    if (view.drawError) throw view.drawError;
    minimap.capture(view);
    viewStatusElement.textContent =
        `VIEW ${message.generationId} completa: ${message.chunkCount} chunks, `
        + formatBytes(Number(message.totalJpegBytes));
}

// 11. El WebSocket distribuye respuestas PAI al catálogo o a la vista activa.
socket.addEventListener("open", () => {
    statusElement.textContent = "Conectado: handshake completado";
    statusElement.parentElement.dataset.state = "connected";
    catalogStatusElement.textContent = "Solicitando catálogo...";
    socket.send(encodeListImages());
});

socket.addEventListener("message", async (event) => {
    let opcode;
    try {
        if (!(event.data instanceof ArrayBuffer)) {
            throw new Error("Se esperaba una respuesta binaria");
        }
        opcode = readPaiOpcode(event.data);
        switch (opcode) {
            case PaiOpcode.IMAGE_LIST:
                showCatalog(decodeImageList(event.data));
                break;
            case PaiOpcode.VIEW_START:
                receiveViewStart(event.data);
                break;
            case PaiOpcode.CHUNK:
                receiveChunk(event.data);
                break;
            case PaiOpcode.VIEW_END:
                await receiveViewEnd(event.data);
                break;
            default:
                throw new Error("Operacion PAI no esperada: " + opcode);
        }
    } catch (error) {
        if (opcode === PaiOpcode.IMAGE_LIST) {
            catalogStatusElement.textContent = "Catálogo rechazado: " + error.message;
        } else {
            viewStatusElement.textContent = "Respuesta VIEW rechazada: " + error.message;
        }
    }
});

socket.addEventListener("close", () => {
    activeView = null;
    minimap.clear();
    bitmapCache.clear();
    updateCacheStatus();
    statusElement.textContent = "Desconectado";
    statusElement.parentElement.dataset.state = "disconnected";
    imageSelect.disabled = true;
    sendValidButton.disabled = true;
    sendInvalidButton.disabled = true;
    updateNavigation();
});

socket.addEventListener("error", () => {
    statusElement.textContent = "Error de conexión";
    statusElement.parentElement.dataset.state = "error";
});

// 12. Los controles traducen acciones del usuario en nuevas solicitudes VIEW.
imageSelect.addEventListener("change", () => {
    if (wheelTimer !== null) clearTimeout(wheelTimer);
    wheelTimer = null;
    const wasViewing = currentRequest !== null;
    currentRequest = null;
    activeView = null;
    latestRequestedGenerationId = 0n;
    canvasContext.clearRect(0, 0, imageCanvas.width, imageCanvas.height);
    minimap.clear();
    showSelectedImage();
    const image = catalog.get(imageSelect.value);
    if (wasViewing && image) requestView(initialView(image));
});

sendValidButton.addEventListener("click", () => {
    const image = catalog.get(imageSelect.value);
    if (image) requestView(initialView(image));
});

zoomOutButton.addEventListener("click", () => changeZoom(-1));
zoomInButton.addEventListener("click", () => changeZoom(1));

imageCanvas.addEventListener("wheel", (event) => {
    if (!currentRequest || socket.readyState !== WebSocket.OPEN) return;
    event.preventDefault();
    if (wheelTimer !== null) clearTimeout(wheelTimer);
    const direction = event.deltaY < 0 ? 1 : -1;
    wheelTimer = setTimeout(() => {
        wheelTimer = null;
        changeZoom(direction);
    }, 150);
}, {passive: false});

imageCanvas.addEventListener("pointerdown", (event) => {
    if (!currentRequest || event.button !== 0) return;
    dragStart = {pointerId: event.pointerId, x: event.clientX, y: event.clientY};
    imageCanvas.setPointerCapture(event.pointerId);
    imageCanvas.classList.add("dragging");
});

imageCanvas.addEventListener("pointerup", (event) => {
    if (!dragStart || dragStart.pointerId !== event.pointerId) return;
    const deltaX = event.clientX - dragStart.x;
    const deltaY = event.clientY - dragStart.y;
    dragStart = null;
    imageCanvas.classList.remove("dragging");
    if (Math.hypot(deltaX, deltaY) < 3) return;
    const bounds = imageCanvas.getBoundingClientRect();
    moveView(deltaX * imageCanvas.width / bounds.width,
        deltaY * imageCanvas.height / bounds.height);
});

imageCanvas.addEventListener("pointercancel", () => {
    dragStart = null;
    imageCanvas.classList.remove("dragging");
});

sendInvalidButton.addEventListener("click", () => {
    if (!requireOpenSocket()) return;
    const image = catalog.get(imageSelect.value);
    if (!image) return;
    const request = {...initialView(image), generationId: generationId++};

    const invalid = new Uint8Array(encodeView(request));
    invalid[0] ^= 0x01;
    socket.send(invalid);
    viewStatusElement.textContent = "VIEW inválido enviado; el servidor debe rechazarlo";
});
