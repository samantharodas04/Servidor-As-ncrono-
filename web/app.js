const canvas = document.getElementById("viewer");
const ctx = canvas.getContext("2d");
const imageSelect = document.getElementById("imageSelect");
const statusEl = document.getElementById("status");
const levelLabel = document.getElementById("levelLabel");
const tileCountEl = document.getElementById("tileCount");
const bytesCountEl = document.getElementById("bytesCount");
const resolutionEl = document.getElementById("resolution");
const processorEl = document.getElementById("processor");
const sourceSizeEl = document.getElementById("sourceSize");

let ws;
let images = [];
let current = null;
let currentLevel = 0;
let offsetX = 0;
let offsetY = 0;
let dragging = false;
let dragStartX = 0;
let dragStartY = 0;
let startOffsetX = 0;
let startOffsetY = 0;

let tileCache = new Map();
let pending = new Set();
let bytesReceived = 0;
let expectedBinary = null;

function sendIrp(command, fields = {}) {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    let msg = `IRP/1.0 ${command}\n`;
    for (const [k, v] of Object.entries(fields)) {
        msg += `${k}: ${v}\n`;
    }
    ws.send(msg);
}

function connect() {
    const proto = location.protocol === "https:" ? "wss" : "ws";
    ws = new WebSocket(`${proto}://${location.host}/irp`);
    ws.binaryType = "arraybuffer";

    ws.onopen = () => {
        statusEl.textContent = "Conectado";
        sendIrp("LIST");
    };

    ws.onclose = () => statusEl.textContent = "Desconectado";
    ws.onerror = () => statusEl.textContent = "Error de conexión";

    ws.onmessage = async (ev) => {
        if (typeof ev.data === "string") {
            handleText(ev.data);
        } else {
            await handleBinary(ev.data);
        }
    };
}

function parseHeaders(text) {
    const lines = text.replace(/\r/g, "").split("\n");
    const obj = { status: lines[0] };
    for (let i = 1; i < lines.length; i++) {
        const idx = lines[i].indexOf(":");
        if (idx > 0) {
            obj[lines[i].slice(0, idx).trim().toLowerCase()] =
                lines[i].slice(idx + 1).trim();
        }
    }
    return obj;
}

function handleText(text) {
    const h = parseHeaders(text);

    if (h.status?.startsWith("IRP/1.0 404") ||
        h.status?.startsWith("IRP/1.0 409") ||
        h.status?.startsWith("IRP/1.0 500")) {
        console.error(text);
        return;
    }

    if (h["content-type"] === "image/jpeg") {
        expectedBinary = {
            image: h.image,
            level: Number(h.level),
            x: Number(h.x),
            y: Number(h.y)
        };
        return;
    }

    if (h.count !== undefined) {
        const ids = [...text.matchAll(/^Image:\s*(.+)$/gm)].map(m => m[1].trim());
        images = ids;
        imageSelect.innerHTML = ids.map(id => `<option>${id}</option>`).join("");
        if (ids.length > 0) {
            selectImage(ids[0]);
        } else {
            statusEl.textContent = "No hay imágenes en images/originals";
        }
        return;
    }

    if (h.width && h.height && h.levels) {
        current = {
            id: h.image,
            width: Number(h.width),
            height: Number(h.height),
            tileSize: Number(h["tile-size"]),
            levels: Number(h.levels),
            sourceBytes: Number(h["source-bytes"] || 0),
            processor: h.processor || "-"
        };
        currentLevel = Math.max(0, current.levels - 2);
        centerImage();
        clearTiles();
        updateUi();
        requestVisibleTiles();
    }
}

async function handleBinary(arrayBuffer) {
    if (!expectedBinary) return;

    const meta = expectedBinary;
    expectedBinary = null;
    const key = tileKey(meta.level, meta.x, meta.y);

    const blob = new Blob([arrayBuffer], { type: "image/jpeg" });
    const bmp = await createImageBitmap(blob);

    pending.delete(key);

    if (meta.level !== currentLevel || meta.image !== current?.id) {
        bmp.close();
        return;
    }

    tileCache.set(key, bmp);
    bytesReceived += arrayBuffer.byteLength;
    tileCountEl.textContent = tileCache.size;
    bytesCountEl.textContent = `${(bytesReceived / 1024).toFixed(1)} KB`;

    draw();
}

function selectImage(id) {
    clearTiles();
    sendIrp("INFO", { Image: id });
}

imageSelect.addEventListener("change", () => selectImage(imageSelect.value));

document.getElementById("minusBtn").addEventListener("click", () => {
    if (!current || currentLevel <= 0) return;
    currentLevel--;
    levelChanged();
});

document.getElementById("plusBtn").addEventListener("click", () => {
    if (!current || currentLevel >= current.levels - 1) return;
    currentLevel++;
    levelChanged();
});

document.getElementById("centerBtn").addEventListener("click", () => {
    centerImage();
    draw();
    requestVisibleTiles();
});

function levelChanged() {
    clearTiles();
    centerImage();
    updateUi();
    draw();
    requestVisibleTiles();
}

function clearTiles() {
    for (const bmp of tileCache.values()) bmp.close();
    tileCache.clear();
    pending.clear();
    tileCountEl.textContent = "0";
}

function levelScale() {
    if (!current) return 1;
    return Math.pow(2, currentLevel - (current.levels - 1));
}

function levelDimensions() {
    const s = levelScale();
    return {
        width: Math.max(1, Math.round(current.width * s)),
        height: Math.max(1, Math.round(current.height * s))
    };
}

function centerImage() {
    if (!current) return;
    const d = levelDimensions();
    offsetX = (canvas.width - d.width) / 2;
    offsetY = (canvas.height - d.height) / 2;
}

function tileKey(level, x, y) {
    return `${level}:${x}:${y}`;
}

function requestVisibleTiles() {
    if (!current) return;
    const d = levelDimensions();
    const ts = current.tileSize;

    const left = Math.max(0, -offsetX);
    const top = Math.max(0, -offsetY);
    const right = Math.min(d.width, canvas.width - offsetX);
    const bottom = Math.min(d.height, canvas.height - offsetY);

    if (right <= left || bottom <= top) return;

    const x0 = Math.max(0, Math.floor(left / ts));
    const y0 = Math.max(0, Math.floor(top / ts));
    const x1 = Math.min(Math.ceil(d.width / ts) - 1, Math.floor((right - 1) / ts));
    const y1 = Math.min(Math.ceil(d.height / ts) - 1, Math.floor((bottom - 1) / ts));

    for (let y = y0; y <= y1; y++) {
        for (let x = x0; x <= x1; x++) {
            const key = tileKey(currentLevel, x, y);
            if (!tileCache.has(key) && !pending.has(key)) {
                pending.add(key);
                sendIrp("TILE", {
                    Image: current.id,
                    Level: currentLevel,
                    X: x,
                    Y: y
                });
            }
        }
    }
}

function draw() {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.fillStyle = "#0b1020";
    ctx.fillRect(0, 0, canvas.width, canvas.height);

    if (!current) return;
    const ts = current.tileSize;

    for (const [key, bmp] of tileCache.entries()) {
        const [level, x, y] = key.split(":").map(Number);
        if (level !== currentLevel) continue;
        ctx.drawImage(bmp, offsetX + x * ts, offsetY + y * ts);
    }

    ctx.strokeStyle = "rgba(255,255,255,.2)";
    ctx.strokeRect(offsetX, offsetY, levelDimensions().width, levelDimensions().height);
}

function updateUi() {
    if (!current) return;
    const d = levelDimensions();
    levelLabel.textContent = `Nivel ${currentLevel + 1}/${current.levels}`;
    resolutionEl.textContent = `${d.width} × ${d.height}`;
    processorEl.textContent = current.processor;
    sourceSizeEl.textContent = formatBytes(current.sourceBytes);
}

canvas.addEventListener("mousedown", e => {
    dragging = true;
    canvas.classList.add("dragging");
    dragStartX = e.offsetX;
    dragStartY = e.offsetY;
    startOffsetX = offsetX;
    startOffsetY = offsetY;
});

window.addEventListener("mouseup", () => {
    dragging = false;
    canvas.classList.remove("dragging");
});

canvas.addEventListener("mousemove", e => {
    if (!dragging) return;
    offsetX = startOffsetX + (e.offsetX - dragStartX);
    offsetY = startOffsetY + (e.offsetY - dragStartY);
    draw();
    requestVisibleTiles();
});

function formatBytes(bytes) {
    if (!bytes) return "-";
    const units = ["B", "KB", "MB", "GB", "TB"];
    let value = bytes;
    let i = 0;
    while (value >= 1024 && i < units.length - 1) {
        value /= 1024;
        i++;
    }
    return `${value.toFixed(i >= 3 ? 2 : 1)} ${units[i]}`;
}

connect();
