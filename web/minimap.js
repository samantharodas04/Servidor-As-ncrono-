"use strict";

import {clampVisibleCenter} from "./navigation.js";

// La miniatura conserva una copia pequeña de zoom 0 y navega por coordenadas del original.
export function createMinimap({panel, frame, canvas, marker, imageCanvas,
        getImage, getRequest, getActiveView, socketReady, zoomScales, requestView, moveView}) {
    const context = canvas.getContext("2d");
    let geometry = null;
    let drag = null;
    let previewImage = null;

    function clear() {
        context.clearRect(0, 0, canvas.width, canvas.height);
        geometry = null;
        drag = null;
        previewImage = null;
        frame.classList.remove("dragging");
        panel.hidden = true;
    }

    function usePreview(imageId, element) {
        if (!element.complete || element.naturalWidth === 0) return;
        previewImage = {imageId, element};
        if (geometry?.imageId === imageId) {
            context.clearRect(0, 0, canvas.width, canvas.height);
            context.drawImage(element, geometry.x, geometry.y,
                geometry.width, geometry.height);
        }
    }

    function capture(view) {
        if (view.zoomIndex !== 0) return;
        const image = getImage(view.imageId);
        if (!image) return;

        const scale = zoomScales(image)[0];
        const sourceWidth = Math.min(imageCanvas.width, Math.max(1,
            Math.round(image.width * scale)));
        const sourceHeight = Math.min(imageCanvas.height, Math.max(1,
            Math.round(image.height * scale)));
        const sourceX = Math.floor((imageCanvas.width - sourceWidth) / 2);
        const sourceY = Math.floor((imageCanvas.height - sourceHeight) / 2);
        const overviewScale = Math.min(canvas.width / image.width, canvas.height / image.height);
        const width = image.width * overviewScale;
        const height = image.height * overviewScale;
        const x = (canvas.width - width) / 2;
        const y = (canvas.height - height) / 2;

        context.clearRect(0, 0, canvas.width, canvas.height);
        if (previewImage?.imageId === image.id) {
            context.drawImage(previewImage.element, x, y, width, height);
        } else {
            // La vista por chunks sirve de respaldo si no hay miniatura preparada.
            context.drawImage(imageCanvas,
                sourceX, sourceY, sourceWidth, sourceHeight, x, y, width, height);
        }
        geometry = {imageId: image.id, x, y, width, height};
        panel.hidden = false;
        updateMarker(view);
    }

    function updateMarker(view) {
        if (!geometry || geometry.imageId !== view.imageId) return;
        const image = getImage(view.imageId);
        if (!image || view.regionX + view.regionWidth > image.width
                || view.regionY + view.regionHeight > image.height) return;

        const rawX = geometry.x + geometry.width * view.regionX / image.width;
        const rawY = geometry.y + geometry.height * view.regionY / image.height;
        const rawWidth = geometry.width * view.regionWidth / image.width;
        const rawHeight = geometry.height * view.regionHeight / image.height;
        const width = Math.max(8, rawWidth);
        const height = Math.max(8, rawHeight);
        const x = Math.max(0, Math.min(canvas.width - width,
            rawX + (rawWidth - width) / 2));
        const y = Math.max(0, Math.min(canvas.height - height,
            rawY + (rawHeight - height) / 2));

        marker.style.left = `${x / canvas.width * 100}%`;
        marker.style.top = `${y / canvas.height * 100}%`;
        marker.style.width = `${width / canvas.width * 100}%`;
        marker.style.height = `${height / canvas.height * 100}%`;
    }

    function target(event) {
        const request = getRequest();
        const activeView = getActiveView();
        if (!request || request.zoomIndex === 0 || !socketReady()
                || !activeView || activeView.generationId !== request.generationId
                || !geometry || geometry.imageId !== request.imageId) return null;
        const image = getImage(request.imageId);
        if (!image) return null;

        const bounds = canvas.getBoundingClientRect();
        const canvasX = (event.clientX - bounds.left) * canvas.width / bounds.width;
        const canvasY = (event.clientY - bounds.top) * canvas.height / bounds.height;
        const sourceX = Math.round(Math.max(0, Math.min(image.width - 1,
            (canvasX - geometry.x) * image.width / geometry.width)));
        const sourceY = Math.round(Math.max(0, Math.min(image.height - 1,
            (canvasY - geometry.y) * image.height / geometry.height)));
        const scale = zoomScales(image)[request.zoomIndex];
        const scaleX = Math.max(1, Math.round(image.width * scale)) / image.width;
        const scaleY = Math.max(1, Math.round(image.height * scale)) / image.height;
        return {
            centerX: clampVisibleCenter(sourceX, image.width, imageCanvas.width / scaleX),
            centerY: clampVisibleCenter(sourceY, image.height, imageCanvas.height / scaleY)
        };
    }

    function preview(position) {
        const request = getRequest();
        const activeView = getActiveView();
        const image = getImage(request.imageId);
        const {regionWidth, regionHeight} = activeView;
        updateMarker({
            ...activeView,
            regionX: Math.max(0, Math.min(image.width - regionWidth,
                Math.round(position.centerX - regionWidth / 2))),
            regionY: Math.max(0, Math.min(image.height - regionHeight,
                Math.round(position.centerY - regionHeight / 2)))
        });
    }

    frame.addEventListener("pointerdown", (event) => {
        if (event.button !== 0) return;
        const position = target(event);
        if (!position) return;
        event.preventDefault();
        frame.focus();
        drag = {pointerId: event.pointerId, generationId: getRequest().generationId};
        frame.setPointerCapture(event.pointerId);
        frame.classList.add("dragging");
        preview(position);
    });

    frame.addEventListener("pointermove", (event) => {
        if (!drag || drag.pointerId !== event.pointerId
                || drag.generationId !== getRequest()?.generationId) return;
        const position = target(event);
        if (position) preview(position);
    });

    frame.addEventListener("pointerup", (event) => {
        if (!drag || drag.pointerId !== event.pointerId) return;
        const generationId = drag.generationId;
        drag = null;
        frame.classList.remove("dragging");
        const request = getRequest();
        if (generationId !== request?.generationId) return;
        const position = target(event);
        if (position && (position.centerX !== request.centerX
                || position.centerY !== request.centerY)) {
            requestView({...request, ...position});
        } else if (getActiveView()) {
            updateMarker(getActiveView());
        }
    });

    frame.addEventListener("pointercancel", () => {
        drag = null;
        frame.classList.remove("dragging");
        if (getActiveView()) updateMarker(getActiveView());
    });

    frame.addEventListener("keydown", (event) => {
        const movement = {
            ArrowLeft: [imageCanvas.width / 4, 0],
            ArrowRight: [-imageCanvas.width / 4, 0],
            ArrowUp: [0, imageCanvas.height / 4],
            ArrowDown: [0, -imageCanvas.height / 4]
        }[event.key];
        if (!movement || !getRequest() || getRequest().zoomIndex === 0) return;
        event.preventDefault();
        moveView(...movement);
    });

    return {clear, usePreview, capture, updateMarker};
}
