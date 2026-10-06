"use strict";

// Escala y centro usan las mismas dimensiones que se envían en VIEW.
export function zoomScales(image, viewportWidth, viewportHeight, extraGiantZoom) {
    const base = Math.min(1, viewportWidth / image.width,
        viewportHeight / image.height);
    const maximum = extraGiantZoom
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

export function initialView(image, viewportWidth, viewportHeight) {
    return {
        imageId: image.id,
        zoomIndex: 0,
        centerX: Math.floor(image.width / 2),
        centerY: Math.floor(image.height / 2),
        viewportWidth: viewportWidth,
        viewportHeight: viewportHeight
    };
}

export function clampVisibleCenter(center, imageSize, visibleSize) {
    if (visibleSize >= imageSize) return Math.floor(imageSize / 2);
    const half = visibleSize / 2;
    return Math.max(0, Math.min(imageSize - 1,
        Math.round(Math.max(half, Math.min(imageSize - half, center)))));
}

