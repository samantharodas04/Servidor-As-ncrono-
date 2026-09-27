"use strict";

// Formato PAI/1: CHUNK(5) lleva JPEG y CHUNK_PNG(7) lleva PNG.
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
    VIEW_END: 6,
    CHUNK_PNG: 7
});

const PaiLimits = Object.freeze({
    MAX_IMAGES: 1024,
    MAX_CHUNKS_PER_VIEW: 4096,
    MAX_CHUNK_BYTES: 8 * 1024 * 1024
});

const PAI_MAGIC = new TextEncoder().encode(PaiProtocol.NAME);
const PAI_HEADER_BYTES = PAI_MAGIC.length + 2;
const VIEW_BODY_BYTES = 8 + (5 * 4) + 2;
const VIEW_START_BYTES = PAI_HEADER_BYTES + 8 + (8 * 4);
const CHUNK_METADATA_BYTES = PAI_HEADER_BYTES + 8 + (8 * 4);
const VIEW_END_BYTES = PAI_HEADER_BYTES + 8 + 4 + 8;

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
    const opcode = readPaiOpcode(buffer);
    if (opcode !== PaiOpcode.CHUNK && opcode !== PaiOpcode.CHUNK_PNG) {
        throw new Error("Operacion CHUNK inesperada");
    }
    const view = new DataView(buffer);
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
    const imageBytes = view.getUint32(offset);
    offset += 4;

    if (generationId < 1n || width < 1 || height < 1 || imageBytes < 8
            || imageBytes > PaiLimits.MAX_CHUNK_BYTES
            || offset + imageBytes !== view.byteLength) {
        throw new Error("CHUNK contiene valores invalidos");
    }
    const image = new Uint8Array(buffer, offset, imageBytes);
    const png = opcode === PaiOpcode.CHUNK_PNG;
    if (png ? !(image[0] === 137 && image[1] === 80 && image[2] === 78
            && image[3] === 71 && image[4] === 13 && image[5] === 10
            && image[6] === 26 && image[7] === 10)
            : !(image[0] === 0xff && image[1] === 0xd8
            && image[image.length - 2] === 0xff && image[image.length - 1] === 0xd9)) {
        throw new Error("CHUNK no contiene una imagen valida");
    }
    return {generationId, index, column, row, canvasX, canvasY, width, height,
        imageBytes, image, png};
}

function decodeViewEnd(buffer) {
    const view = requirePaiMessage(buffer, PaiOpcode.VIEW_END);
    if (view.byteLength !== VIEW_END_BYTES) {
        throw new Error("VIEW_END tiene una longitud invalida");
    }
    return {
        generationId: view.getBigUint64(PAI_HEADER_BYTES),
        chunkCount: view.getUint32(PAI_HEADER_BYTES + 8),
        totalImageBytes: view.getBigUint64(PAI_HEADER_BYTES + 12)
    };
}

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

export {PaiOpcode, encodeListImages, encodeView, readPaiOpcode,
    decodeImageList, decodeViewStart, decodeChunk, decodeViewEnd};
