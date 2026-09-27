"use strict";

// Cambiar a false y recargar la página para detener el zoom gigante en 1:1.
const ENABLE_EXTRA_GIANT_ZOOM = true;

// Paso 1
const PaiProtocol = Object.freeze({
    NAME: "PAI",
    VERSION: 1
});

const PaiOpcode = Object.freeze({
    VIEW: 1,
    LIST_IMAGES: 2,
    IMAGE_LIST: 3,
    VIEW_START: 4,
    CHUNK: 5,
    VIEW_END: 6
});

const PaiLimits = Object.freeze({
    MAX_IMAGES: 1024,
    MAX_CHUNKS_PER_VIEW: 4096,
    MAX_JPEG_BYTES: 8 * 1024 * 1024
});

const PAI_MAGIC = new TextEncoder().encode(PaiProtocol.NAME);
const PAI_HEADER_BYTES = PAI_MAGIC.length + 2;
const VIEW_BODY_BYTES = 8 + (5 * 4) + 2;
const VIEW_START_BYTES = PAI_HEADER_BYTES + 8 + (8 * 4);
const CHUNK_METADATA_BYTES = PAI_HEADER_BYTES + 8 + (8 * 4);
const VIEW_END_BYTES = PAI_HEADER_BYTES + 8 + 4 + 8;

// Paso 2
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
const bitmapCache = new window.BitmapCache(12 * 1024 * 1024);

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

// Paso 3
function createPaiMessage(opcode, payloadBytes = 0) {
    const buffer = new ArrayBuffer(PAI_HEADER_BYTES + payloadBytes);
    const bytes = new Uint8Array(buffer);
    const view = new DataView(buffer);

    bytes.set(PAI_MAGIC, 0);
    view.setUint8(PAI_MAGIC.length, PaiProtocol.VERSION);
    view.setUint8(PAI_MAGIC.length + 1, opcode);

    return {buffer, view, offset: PAI_HEADER_BYTES};
}

function readPaiOpcode(buffer, minimumPayloadBytes = 0) {
    const view = new DataView(buffer);
    if (view.byteLength < PAI_HEADER_BYTES + minimumPayloadBytes) {
        throw new Error("Mensaje PAI incompleto");
    }

    for (let index = 0; index < PAI_MAGIC.length; index++) {
        if (view.getUint8(index) !== PAI_MAGIC[index]) {
            throw new Error("Firma PAI invalida");
        }
    }
    if (view.getUint8(PAI_MAGIC.length) !== PaiProtocol.VERSION) {
        throw new Error("Version PAI no soportada");
    }
    return view.getUint8(PAI_MAGIC.length + 1);
}

function requirePaiMessage(buffer, expectedOpcode, minimumPayloadBytes = 0) {
    const opcode = readPaiOpcode(buffer, minimumPayloadBytes);
    if (opcode !== expectedOpcode) {
        throw new Error("Operacion PAI inesperada");
    }
    return new DataView(buffer);
}

// Paso 4
function encodeListImages() {
    return createPaiMessage(PaiOpcode.LIST_IMAGES).buffer;
}

function readText(view, state) {
    if (state.offset + 2 > view.byteLength) {
        throw new Error("IMAGE_LIST incompleto");
    }
    const length = view.getUint16(state.offset);
    state.offset += 2;
    if (length < 1 || state.offset + length > view.byteLength) {
        throw new Error("Texto invalido en IMAGE_LIST");
    }

    const bytes = new Uint8Array(view.buffer, state.offset, length);
    state.offset += length;
    return new TextDecoder("utf-8", {fatal: true}).decode(bytes);
}

function decodeImageList(buffer) {
    const view = requirePaiMessage(buffer, PaiOpcode.IMAGE_LIST, 2);
    const count = view.getUint16(PAI_HEADER_BYTES);
    if (count > PaiLimits.MAX_IMAGES) {
        throw new Error("IMAGE_LIST excede el maximo de imagenes");
    }

    const state = {offset: PAI_HEADER_BYTES + 2};
    const images = [];
    for (let index = 0; index < count; index++) {
        const id = readText(view, state);
        const name = readText(view, state);
        if (state.offset + 16 > view.byteLength) {
            throw new Error("Metadatos incompletos en IMAGE_LIST");
        }

        const width = view.getUint32(state.offset);
        state.offset += 4;
        const height = view.getUint32(state.offset);
        state.offset += 4;
        const sizeBytes = Number(view.getBigUint64(state.offset));
        state.offset += 8;
        if (width < 1 || height < 1 || !Number.isSafeInteger(sizeBytes)) {
            throw new Error("Metadatos invalidos en IMAGE_LIST");
        }
        images.push({id, name, width, height, sizeBytes});
    }

    if (state.offset !== view.byteLength) {
        throw new Error("IMAGE_LIST contiene bytes adicionales");
    }
    return images;
}

function decodeViewStart(buffer) {
    const view = requirePaiMessage(buffer, PaiOpcode.VIEW_START);
    if (view.byteLength !== VIEW_START_BYTES) {
        throw new Error("VIEW_START tiene una longitud invalida");
    }

    let offset = PAI_HEADER_BYTES;
    const generationId = view.getBigUint64(offset);
    offset += 8;
    const viewportWidth = view.getUint32(offset);
    offset += 4;
    const viewportHeight = view.getUint32(offset);
    offset += 4;
    const zoomIndex = view.getUint32(offset);
    offset += 4;
    const regionX = view.getUint32(offset);
    offset += 4;
    const regionY = view.getUint32(offset);
    offset += 4;
    const regionWidth = view.getUint32(offset);
    offset += 4;
    const regionHeight = view.getUint32(offset);
    offset += 4;
    const chunkCount = view.getUint32(offset);

    if (generationId < 1n || viewportWidth < 1 || viewportHeight < 1
            || regionWidth < 1 || regionHeight < 1
            || chunkCount < 1 || chunkCount > PaiLimits.MAX_CHUNKS_PER_VIEW) {
        throw new Error("VIEW_START contiene valores invalidos");
    }
    return {
        generationId,
        viewportWidth,
        viewportHeight,
        zoomIndex,
        regionX,
        regionY,
        regionWidth,
        regionHeight,
        chunkCount
    };
}

function decodeChunk(buffer) {
    const view = requirePaiMessage(buffer, PaiOpcode.CHUNK);
    if (view.byteLength < CHUNK_METADATA_BYTES) {
        throw new Error("CHUNK incompleto");
    }

    let offset = PAI_HEADER_BYTES;
    const generationId = view.getBigUint64(offset);
    offset += 8;
    const index = view.getUint32(offset);
    offset += 4;
    const column = view.getUint32(offset);
    offset += 4;
    const row = view.getUint32(offset);
    offset += 4;
    const canvasX = view.getInt32(offset);
    offset += 4;
    const canvasY = view.getInt32(offset);
    offset += 4;
    const width = view.getUint32(offset);
    offset += 4;
    const height = view.getUint32(offset);
    offset += 4;
    const jpegBytes = view.getUint32(offset);
    offset += 4;

    if (generationId < 1n || width < 1 || height < 1 || jpegBytes < 4
            || jpegBytes > PaiLimits.MAX_JPEG_BYTES
            || offset + jpegBytes !== view.byteLength) {
        throw new Error("CHUNK contiene valores invalidos");
    }
    const jpeg = new Uint8Array(buffer, offset, jpegBytes);
    if (jpeg[0] !== 0xff || jpeg[1] !== 0xd8
            || jpeg[jpeg.length - 2] !== 0xff || jpeg[jpeg.length - 1] !== 0xd9) {
        throw new Error("CHUNK no contiene un JPEG valido");
    }
    return {generationId, index, column, row, canvasX, canvasY, width, height, jpegBytes, jpeg};
}

function decodeViewEnd(buffer) {
    const view = requirePaiMessage(buffer, PaiOpcode.VIEW_END);
    if (view.byteLength !== VIEW_END_BYTES) {
        throw new Error("VIEW_END tiene una longitud invalida");
    }
    return {
        generationId: view.getBigUint64(PAI_HEADER_BYTES),
        chunkCount: view.getUint32(PAI_HEADER_BYTES + 8),
        totalJpegBytes: view.getBigUint64(PAI_HEADER_BYTES + 12)
    };
}

// Paso 5
function encodeView(request) {
    const imageId = new TextEncoder().encode(request.imageId);
    const message = createPaiMessage(
        PaiOpcode.VIEW,
        VIEW_BODY_BYTES + imageId.length
    );
    const {buffer, view} = message;
    let {offset} = message;

    view.setBigUint64(offset, request.generationId);
    offset += 8;
    view.setInt32(offset, request.zoomIndex);
    offset += 4;
    view.setInt32(offset, request.centerX);
    offset += 4;
    view.setInt32(offset, request.centerY);
    offset += 4;
    view.setInt32(offset, request.viewportWidth);
    offset += 4;
    view.setInt32(offset, request.viewportHeight);
    offset += 4;
    view.setUint16(offset, imageId.length);
    offset += 2;
    new Uint8Array(buffer, offset).set(imageId);
    return buffer;
}

function zoomScales(image) {
    const base = Math.min(1, imageCanvas.width / image.width,
        imageCanvas.height / image.height);
    const maximum = ENABLE_EXTRA_GIANT_ZOOM
        && Math.max(image.width, image.height) >= 50000 ? 4 : 1;
    const scales = [];
    let scale = base;
    for (let index = 0; index < 64; index++) {
        scales.push(scale);
        if (scale >= maximum - 1e-12) break;
        scale = scale < 1 - 1e-12
            ? Math.min(1, scale * 2)
            : Math.min(maximum, scale * 2);
    }
    return scales;
}

function initialView(image) {
    return {
        imageId: image.id,
        zoomIndex: 0,
        centerX: Math.floor(image.width / 2),
        centerY: Math.floor(image.height / 2),
        viewportWidth: imageCanvas.width,
        viewportHeight: imageCanvas.height
    };
}

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

function clampVisibleCenter(center, imageSize, visibleSize) {
    if (visibleSize >= imageSize) return Math.floor(imageSize / 2);
    const half = visibleSize / 2;
    return Math.max(0, Math.min(imageSize - 1,
        Math.round(Math.max(half, Math.min(imageSize - half, center)))));
}

// Paso 6
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
    viewStatusElement.textContent =
        `VIEW ${message.generationId} completa: ${message.chunkCount} chunks, `
        + formatBytes(Number(message.totalJpegBytes));
}

// Paso 7
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

// Paso 8
imageSelect.addEventListener("change", () => {
    if (wheelTimer !== null) clearTimeout(wheelTimer);
    wheelTimer = null;
    const wasViewing = currentRequest !== null;
    currentRequest = null;
    activeView = null;
    latestRequestedGenerationId = 0n;
    canvasContext.clearRect(0, 0, imageCanvas.width, imageCanvas.height);
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
