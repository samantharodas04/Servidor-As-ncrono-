package server;

import image.ImageSource;
import protocol.ImageCatalogCodec;
import protocol.ViewResponseCodec;
import protocol.ViewMessageCodec;
import view.ViewCoordinator;
import view.ViewProcessor;
import view.ViewRequest;
import view.ViewResult;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/** Servidor HTTP asincrono que recibe mensajes binarios PAI en /pai. */
public final class AsyncHttpServer implements AutoCloseable {
    private static final int HTTP_BUFFER_BYTES = 16 * 1024;
    private static final int WEBSOCKET_BUFFER_BYTES = 4 * 1024;
    private static final int CHUNK_SIZE = 512;
    private static final int SERVER_THREADS = 2;
    private static final int VIEW_WORKERS = 2;
    private static final long MAX_CACHE_BYTES = 32L * 1024L * 1024L;
    private static final long MAX_QUEUED_WRITE_BYTES = 32L * 1024L * 1024L;

    private final int port;
    private final Path webRoot;
    private final List<ImageSource> images;
    private final Map<AsynchronousSocketChannel, ClientSession> sessions;
    private final ViewProcessor viewProcessor;

    private AsynchronousChannelGroup channelGroup;
    private AsynchronousServerSocketChannel serverChannel;
    private volatile boolean running;

    public AsyncHttpServer(int port, Path webRoot, List<ImageSource> images) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Puerto fuera de rango");
        }
        this.port = port;
        this.webRoot = webRoot.toAbsolutePath().normalize();
        this.images = List.copyOf(images);
        this.sessions = new ConcurrentHashMap<>();
        this.viewProcessor = new ViewProcessor(images, VIEW_WORKERS, MAX_CACHE_BYTES);
    }

    public synchronized void start() throws IOException {
        if (running) {
            throw new IllegalStateException("El servidor ya esta iniciado");
        }
        if (!Files.isDirectory(webRoot)) {
            throw new IOException("No existe el directorio web: " + webRoot);
        }

        channelGroup = AsynchronousChannelGroup.withFixedThreadPool(
                SERVER_THREADS,
                Executors.defaultThreadFactory()
        );
        serverChannel = AsynchronousServerSocketChannel.open(channelGroup)
                .bind(new InetSocketAddress(port));
        running = true;
        acceptNext();
    }

    private void acceptNext() {
        if (!running) {
            return;
        }
        serverChannel.accept(null, new CompletionHandler<>() {
            @Override
            public void completed(AsynchronousSocketChannel client, Object attachment) {
                acceptNext();
                readHttpRequest(client);
            }

            @Override
            public void failed(Throwable failure, Object attachment) {
                if (running) {
                    System.err.println("No se pudo aceptar una conexion: " + failure.getMessage());
                    acceptNext();
                }
            }
        });
    }

    private void readHttpRequest(AsynchronousSocketChannel client) {
        ByteBuffer buffer = ByteBuffer.allocate(HTTP_BUFFER_BYTES);
        client.read(buffer, buffer, new CompletionHandler<>() {
            @Override
            public void completed(Integer bytesRead, ByteBuffer requestBuffer) {
                if (bytesRead == null || bytesRead < 0) {
                    closeClient(client);
                    return;
                }

                String raw = new String(
                        requestBuffer.array(),
                        0,
                        requestBuffer.position(),
                        StandardCharsets.ISO_8859_1
                );
                if (!raw.contains("\r\n\r\n")) {
                    if (!requestBuffer.hasRemaining()) {
                        writeAndClose(client, httpResponse(
                                431,
                                "text/plain; charset=utf-8",
                                "Cabeceras demasiado grandes".getBytes(StandardCharsets.UTF_8)
                        ));
                    } else {
                        client.read(requestBuffer, requestBuffer, this);
                    }
                    return;
                }

                handleHttpRequest(client, raw);
            }

            @Override
            public void failed(Throwable failure, ByteBuffer requestBuffer) {
                closeClient(client);
            }
        });
    }

    private void handleHttpRequest(AsynchronousSocketChannel client, String raw) {
        try {
            String[] lines = raw.split("\r\n");
            String[] requestLine = lines[0].split(" ");
            if (requestLine.length != 3) {
                writeAndClose(client, textResponse(400, "Solicitud HTTP invalida"));
                return;
            }

            String method = requestLine[0];
            String target = requestLine[1];
            String path = target.split("\\?", 2)[0];
            Map<String, String> headers = parseHeaders(lines);
            System.out.printf("[HTTP] %s %s%n", method, path);

            if ("/pai".equals(path) && isWebSocketRequest(method, headers)) {
                upgradeWebSocket(client, headers);
                return;
            }

            if (!"GET".equals(method)) {
                writeAndClose(client, textResponse(405, "Metodo no permitido"));
                return;
            }

            serveStaticFile(client, path);
        } catch (Exception e) {
            System.err.println("Solicitud rechazada: " + e.getMessage());
            writeAndClose(client, textResponse(400, "Solicitud invalida"));
        }
    }

    private Map<String, String> parseHeaders(String[] lines) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (int index = 1; index < lines.length; index++) {
            int separator = lines[index].indexOf(':');
            if (separator > 0) {
                headers.put(
                        lines[index].substring(0, separator).trim(),
                        lines[index].substring(separator + 1).trim()
                );
            }
        }
        return headers;
    }

    private boolean isWebSocketRequest(String method, Map<String, String> headers) {
        return "GET".equals(method)
                && "websocket".equalsIgnoreCase(headers.getOrDefault("Upgrade", ""))
                && containsToken(headers.getOrDefault("Connection", ""), "upgrade");
    }

    private boolean containsToken(String header, String expected) {
        for (String token : header.split(",")) {
            if (expected.equalsIgnoreCase(token.trim())) {
                return true;
            }
        }
        return false;
    }

    private void upgradeWebSocket(
            AsynchronousSocketChannel client,
            Map<String, String> headers
    ) throws Exception {
        String version = headers.get("Sec-WebSocket-Version");
        String key = headers.get("Sec-WebSocket-Key");
        if (!"13".equals(version) || key == null || key.isBlank()) {
            writeAndClose(client, textResponse(400, "Handshake WebSocket invalido"));
            return;
        }

        String accept = WebSocketUtil.acceptKey(key);
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n"
                + "\r\n";

        writeAll(
                client,
                ByteBuffer.wrap(response.getBytes(StandardCharsets.ISO_8859_1)),
                () -> {
                    sessions.put(client, new ClientSession(client));
                    System.out.println("[WebSocket] Handshake PAI completado");
                    readWebSocketFrames(client);
                }
        );
    }

    /** Mantiene una sola lectura asincrona y conserva frames que lleguen incompletos. */
    private void readWebSocketFrames(AsynchronousSocketChannel client) {
        ByteBuffer input = ByteBuffer.allocate(WEBSOCKET_BUFFER_BYTES);
        client.read(input, input, new CompletionHandler<>() {
            @Override
            public void completed(Integer bytesRead, ByteBuffer buffer) {
                if (bytesRead == null || bytesRead < 0) {
                    closeClient(client);
                    return;
                }

                try {
                    WebSocketUtil.DecodedFrame frame;
                    while ((frame = WebSocketUtil.tryDecode(buffer)) != null) {
                        if (!handleWebSocketFrame(client, frame)) {
                            return;
                        }
                    }

                    if (!buffer.hasRemaining()) {
                        rejectWebSocket(client, "Frame demasiado grande");
                        return;
                    }
                    client.read(buffer, buffer, this);
                } catch (IOException | IllegalArgumentException e) {
                    rejectWebSocket(client, e.getMessage());
                }
            }

            @Override
            public void failed(Throwable failure, ByteBuffer buffer) {
                closeClient(client);
            }
        });
    }

    /** Devuelve false cuando el frame cierra o invalida esta conexion. */
    private boolean handleWebSocketFrame(
            AsynchronousSocketChannel client,
            WebSocketUtil.DecodedFrame frame
    ) throws IOException {
        if (!frame.masked()) {
            throw new IOException("El frame del cliente debe venir enmascarado");
        }
        if (!frame.finalFrame()) {
            throw new IOException("Los frames fragmentados aun no son soportados");
        }

        int opcode = Byte.toUnsignedInt(frame.opcode());
        if (opcode == 0x8) {
            closeClient(client);
            return false;
        }
        if (opcode != 0x2) {
            throw new IOException("Se esperaba un frame binario WebSocket");
        }

        if (ImageCatalogCodec.isListImagesRequest(frame.payload())) {
            byte[] response = ImageCatalogCodec.encodeImageList(images);
            requireSession(client).send(response);
            System.out.println("[PAI] IMAGE_LIST encolado | imagenes=" + images.size());
            return true;
        }

        ViewRequest request = ViewMessageCodec.decode(frame.payload(), CHUNK_SIZE);
        System.out.printf(
                "[PAI] VIEW recibido | generationId=%d | imageId=%s | zoomIndex=%d "
                        + "| center=(%d,%d) | viewport=%dx%d | chunkSize=%d%n",
                request.generationId(),
                request.imageId(),
                request.zoomIndex(),
                request.centerX(),
                request.centerY(),
                request.viewportWidth(),
                request.viewportHeight(),
                request.chunkSize()
        );

        ClientSession session = requireSession(client);
        session.coordinator.submit(
                request,
                result -> sendViewResult(session, result),
                failure -> printViewFailure(session, request, failure)
        );
        return true;
    }

    private ClientSession requireSession(AsynchronousSocketChannel client) throws IOException {
        ClientSession session = sessions.get(client);
        if (session == null) {
            throw new IOException("La conexion no tiene una sesion PAI activa");
        }
        return session;
    }

    private void sendViewResult(ClientSession session, ViewResult result) {
        long generationId = result.request().generationId();
        if (sessions.get(session.client) != session
                || !session.coordinator.isCurrent(generationId)) {
            return;
        }

        try {
            session.send(ViewResponseCodec.encodeViewStart(result));
            for (int index = 0; index < result.chunks().size(); index++) {
                if (sessions.get(session.client) != session
                        || !session.coordinator.isCurrent(generationId)) {
                    return;
                }
                session.send(ViewResponseCodec.encodeChunk(result, index));
            }
            session.send(ViewResponseCodec.encodeViewEnd(result));
        } catch (IOException failure) {
            System.err.println("[PAI] No se pudo encolar VIEW: " + failure.getMessage());
            closeClient(session.client);
            return;
        }

        System.out.printf(
                "[VIEW] enviada | generationId=%d | imageId=%s | zoomIndex=%d "
                        + "| region=(%d,%d %dx%d) | chunks=%d | cache=%d "
                        + "| generados=%d | jpegBytes=%d | preparar=%dms "
                        + "| chunks=%dms | total=%dms%n",
                generationId,
                result.source().id(),
                result.request().zoomIndex(),
                result.region().x(),
                result.region().y(),
                result.region().width(),
                result.region().height(),
                result.chunks().size(),
                result.cacheHits(),
                result.generatedChunks(),
                result.totalJpegBytes(),
                result.preparationMillis(),
                result.chunkMillis(),
                result.totalMillis()
        );
    }

    private void printViewFailure(
            ClientSession session,
            ViewRequest request,
            Throwable failure
    ) {
        if (sessions.get(session.client) != session
                || !session.coordinator.isCurrent(request.generationId())) {
            return;
        }
        System.err.printf(
                "[VIEW] fallo | generationId=%d | imageId=%s | motivo=%s%n",
                request.generationId(),
                request.imageId(),
                failure.getMessage()
        );
    }

    private void rejectWebSocket(AsynchronousSocketChannel client, String reason) {
        System.err.println("[PAI] Mensaje rechazado: " + reason);
        closeClient(client);
    }

    private void serveStaticFile(AsynchronousSocketChannel client, String path) throws IOException {
        String resource = "/".equals(path) ? "/index.html" : path;
        if (!resource.startsWith("/") || resource.indexOf('\0') >= 0) {
            writeAndClose(client, textResponse(400, "Ruta invalida"));
            return;
        }

        Path requested = webRoot.resolve(resource.substring(1)).normalize();
        if (!requested.startsWith(webRoot)
                || !Files.isRegularFile(requested)) {
            writeAndClose(client, textResponse(404, "Recurso no encontrado"));
            return;
        }

        byte[] body = Files.readAllBytes(requested);
        writeAndClose(client, httpResponse(200, mimeType(requested), body));
    }

    private byte[] textResponse(int status, String message) {
        return httpResponse(
                status,
                "text/plain; charset=utf-8",
                message.getBytes(StandardCharsets.UTF_8)
        );
    }

    private byte[] httpResponse(int status, String contentType, byte[] body) {
        String reason = switch (status) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 431 -> "Request Header Fields Too Large";
            default -> "Internal Server Error";
        };
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        byte[] header = head.getBytes(StandardCharsets.ISO_8859_1);
        byte[] response = new byte[header.length + body.length];
        System.arraycopy(header, 0, response, 0, header.length);
        System.arraycopy(body, 0, response, header.length, body.length);
        return response;
    }

    private String mimeType(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (name.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (name.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        return "application/octet-stream";
    }

    private void writeAndClose(AsynchronousSocketChannel client, byte[] response) {
        writeAll(client, ByteBuffer.wrap(response), () -> closeClient(client));
    }

    private void writeAll(
            AsynchronousSocketChannel client,
            ByteBuffer output,
            Runnable completed
    ) {
        client.write(output, output, new CompletionHandler<>() {
            @Override
            public void completed(Integer bytesWritten, ByteBuffer buffer) {
                if (bytesWritten == null || bytesWritten < 0) {
                    closeClient(client);
                } else if (buffer.hasRemaining()) {
                    client.write(buffer, buffer, this);
                } else {
                    completed.run();
                }
            }

            @Override
            public void failed(Throwable failure, ByteBuffer buffer) {
                closeClient(client);
            }
        });
    }

    private void closeClient(AsynchronousSocketChannel client) {
        ClientSession session = sessions.remove(client);
        if (session != null) {
            session.close();
        }
        try {
            client.close();
        } catch (IOException ignored) {
            // La conexion ya estaba cerrada.
        }
    }

    @Override
    public synchronized void close() {
        running = false;
        for (AsynchronousSocketChannel client : List.copyOf(sessions.keySet())) {
            closeClient(client);
        }
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (IOException ignored) {
                // El socket de escucha ya estaba cerrado.
            }
        }
        if (channelGroup != null) {
            try {
                channelGroup.shutdownNow();
            } catch (IOException ignored) {
                // El grupo asincrono ya estaba cerrado.
            }
        }
        viewProcessor.close();
    }

    /** Estado de una conexion: coordinador propio y una sola escritura a la vez. */
    private final class ClientSession implements AutoCloseable {
        private final AsynchronousSocketChannel client;
        private final ViewCoordinator coordinator;
        private final ArrayDeque<byte[]> pendingPayloads;
        private long queuedBytes;
        private boolean writing;
        private boolean closed;

        private ClientSession(AsynchronousSocketChannel client) {
            this.client = client;
            this.coordinator = ViewCoordinator.usingSharedProcessor(viewProcessor);
            this.pendingPayloads = new ArrayDeque<>();
        }

        private void send(byte[] payload) throws IOException {
            boolean startWriting;
            synchronized (this) {
                if (closed) {
                    throw new IOException("La sesion PAI ya esta cerrada");
                }
                if (payload.length > MAX_QUEUED_WRITE_BYTES - queuedBytes) {
                    throw new IOException("La cola de salida excedio su limite");
                }

                pendingPayloads.addLast(payload);
                queuedBytes += payload.length;
                startWriting = !writing;
                if (startWriting) {
                    writing = true;
                }
            }

            if (startWriting) {
                writeNext();
            }
        }

        private void writeNext() {
            byte[] payload;
            synchronized (this) {
                if (closed) {
                    return;
                }
                payload = pendingPayloads.peekFirst();
                if (payload == null) {
                    writing = false;
                    return;
                }
            }
            writeFrame(WebSocketUtil.frame((byte) 0x2, payload));
        }

        private void writeFrame(ByteBuffer frame) {
            client.write(frame, frame, new CompletionHandler<>() {
                @Override
                public void completed(Integer bytesWritten, ByteBuffer pendingFrame) {
                    if (bytesWritten == null || bytesWritten < 0) {
                        closeClient(client);
                    } else if (pendingFrame.hasRemaining()) {
                        client.write(pendingFrame, pendingFrame, this);
                    } else {
                        synchronized (ClientSession.this) {
                            if (closed) {
                                return;
                            }
                            byte[] completed = pendingPayloads.pollFirst();
                            if (completed == null) {
                                writing = false;
                                return;
                            }
                            queuedBytes -= completed.length;
                        }
                        writeNext();
                    }
                }

                @Override
                public void failed(Throwable failure, ByteBuffer pendingFrame) {
                    closeClient(client);
                }
            });
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                pendingPayloads.clear();
                queuedBytes = 0;
            }
            coordinator.close();
        }
    }
}
