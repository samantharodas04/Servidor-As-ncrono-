package server;

import image.ImageManager;
import protocol.IrpProtocol;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class AsyncHttpServer {
    private final int port;
    private final Path webRoot;
    private final ImageManager imageManager;
    private AsynchronousChannelGroup group;
    private AsynchronousServerSocketChannel server;
    private final ExecutorService workers = Executors.newFixedThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors())
    );

    public AsyncHttpServer(int port, String webRoot, ImageManager imageManager) {
        this.port = port;
        this.webRoot = Paths.get(webRoot).toAbsolutePath().normalize();
        this.imageManager = imageManager;
    }

    public void start() throws Exception {
        group = AsynchronousChannelGroup.withFixedThreadPool(
                Math.max(4, Runtime.getRuntime().availableProcessors()),
                Executors.defaultThreadFactory()
        );
        server = AsynchronousServerSocketChannel.open(group)
                .bind(new InetSocketAddress(port));
        acceptNext();
    }

    public void stop() throws Exception {
        if (server != null) server.close();
        if (group != null) group.shutdownNow();
        workers.shutdownNow();
    }

    private void acceptNext() {
        server.accept(null, new CompletionHandler<>() {
            @Override
            public void completed(AsynchronousSocketChannel client, Object attachment) {
                acceptNext();
                readHttp(client);
            }

            @Override
            public void failed(Throwable exc, Object attachment) {
                if (server != null && server.isOpen()) {
                    exc.printStackTrace();
                    acceptNext();
                }
            }
        });
    }

    private void readHttp(AsynchronousSocketChannel client) {
        ByteBuffer buffer = ByteBuffer.allocate(16384);
        client.read(buffer, buffer, new CompletionHandler<>() {
            @Override
            public void completed(Integer bytes, ByteBuffer buf) {
                if (bytes == -1) {
                    close(client);
                    return;
                }
                String raw = new String(buf.array(), 0, buf.position(), StandardCharsets.ISO_8859_1);
                if (!raw.contains("\r\n\r\n")) {
                    client.read(buf, buf, this);
                    return;
                }
                handleHttp(client, raw);
            }

            @Override
            public void failed(Throwable exc, ByteBuffer attachment) {
                close(client);
            }
        });
    }

    private void handleHttp(AsynchronousSocketChannel client, String raw) {
        workers.submit(() -> {
            try {
                String[] lines = raw.split("\r\n");
                String[] requestLine = lines[0].split(" ");
                if (requestLine.length < 2) {
                    writeAndClose(client, response(400, "text/plain", "Bad Request".getBytes(StandardCharsets.UTF_8)));
                    return;
                }

                String method = requestLine[0];
                String path = requestLine[1];
                System.out.println("[HTTP] " + method + " " + path);
                Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                for (int i = 1; i < lines.length; i++) {
                    int colon = lines[i].indexOf(':');
                    if (colon > 0) {
                        headers.put(lines[i].substring(0, colon).trim(),
                                lines[i].substring(colon + 1).trim());
                    }
                }

                if ("/irp".equals(path)
                        && "websocket".equalsIgnoreCase(headers.getOrDefault("Upgrade", ""))) {
                    upgradeWebSocket(client, headers);
                    return;
                }

                if (!"GET".equalsIgnoreCase(method)) {
                    writeAndClose(client, response(405, "text/plain", "Method Not Allowed".getBytes(StandardCharsets.UTF_8)));
                    return;
                }

                if ("/".equals(path)) path = "/index.html";
                Path requested = webRoot.resolve(path.substring(1)).normalize();
                if (!requested.startsWith(webRoot) || !Files.exists(requested) || Files.isDirectory(requested)) {
                    writeAndClose(client, response(404, "text/plain", "Not Found".getBytes(StandardCharsets.UTF_8)));
                    return;
                }

                byte[] body = Files.readAllBytes(requested);
                writeAndClose(client, response(200, mime(requested), body));

            } catch (Exception e) {
                e.printStackTrace();
                writeAndClose(client, response(500, "text/plain",
                        "Internal Server Error".getBytes(StandardCharsets.UTF_8)));
            }
        });
    }

    private void upgradeWebSocket(AsynchronousSocketChannel client, Map<String, String> headers) throws Exception {
        String key = headers.get("Sec-WebSocket-Key");
        if (key == null) {
            writeAndClose(client, response(400, "text/plain", "Missing WebSocket key".getBytes(StandardCharsets.UTF_8)));
            return;
        }

        String accept = WebSocketUtil.acceptKey(key);
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";

        ByteBuffer out = ByteBuffer.wrap(response.getBytes(StandardCharsets.ISO_8859_1));
        writeAll(client, out, () -> startWebSocket(client));
    }

    private void startWebSocket(AsynchronousSocketChannel client) {
        IrpProtocol protocol = new IrpProtocol(imageManager);
        ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
        processWebSocketBuffer(client, buffer, protocol);
    }

    /*
     * Primero intenta consumir frames que ya están almacenados en el buffer.
     * Esto es importante porque un navegador puede enviar varios comandos TILE
     * en un mismo paquete TCP. Solo se vuelve a leer del socket cuando no existe
     * un frame completo pendiente de procesar.
     */
    private void processWebSocketBuffer(AsynchronousSocketChannel client,
                                        ByteBuffer buffer,
                                        IrpProtocol protocol) {
        WebSocketUtil.DecodedFrame frame = WebSocketUtil.tryDecode(buffer);

        if (frame != null) {
            if (frame.opcode() == 0x8) {
                close(client);
                return;
            }

            if (frame.opcode() == 0x1) {
                String message = new String(frame.payload(), StandardCharsets.UTF_8);
                String firstLine = message.replace("\r", "").split("\n", 2)[0];
                System.out.println("[IRP] " + firstLine);
                List<IrpProtocol.Response> responses = protocol.handle(message);
                sendResponses(client, responses, 0,
                        () -> processWebSocketBuffer(client, buffer, protocol));
                return;
            }

            processWebSocketBuffer(client, buffer, protocol);
            return;
        }

        client.read(buffer, buffer, new CompletionHandler<>() {
            @Override
            public void completed(Integer bytes, ByteBuffer buf) {
                if (bytes == -1) {
                    close(client);
                    return;
                }
                processWebSocketBuffer(client, buf, protocol);
            }

            @Override
            public void failed(Throwable exc, ByteBuffer attachment) {
                close(client);
            }
        });
    }

    private void sendResponses(AsynchronousSocketChannel client,
                               List<IrpProtocol.Response> responses,
                               int index,
                               Runnable done) {
        if (index >= responses.size()) {
            done.run();
            return;
        }
        IrpProtocol.Response r = responses.get(index);
        byte opcode = r.binary ? (byte) 0x2 : (byte) 0x1;
        ByteBuffer frame = WebSocketUtil.frame(opcode, r.data);
        writeAll(client, frame, () -> sendResponses(client, responses, index + 1, done));
    }

    private byte[] response(int code, String contentType, byte[] body) {
        String reason = switch (code) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            default -> "Internal Server Error";
        };
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        byte[] h = head.getBytes(StandardCharsets.ISO_8859_1);
        byte[] result = new byte[h.length + body.length];
        System.arraycopy(h, 0, result, 0, h.length);
        System.arraycopy(body, 0, result, h.length, body.length);
        return result;
    }

    private void writeAndClose(AsynchronousSocketChannel client, byte[] data) {
        writeAll(client, ByteBuffer.wrap(data), () -> close(client));
    }

    private void writeAll(AsynchronousSocketChannel client, ByteBuffer buffer, Runnable done) {
        client.write(buffer, buffer, new CompletionHandler<>() {
            @Override
            public void completed(Integer bytes, ByteBuffer buf) {
                if (bytes == -1) {
                    close(client);
                    return;
                }
                if (buf.hasRemaining()) {
                    client.write(buf, buf, this);
                } else {
                    done.run();
                }
            }

            @Override
            public void failed(Throwable exc, ByteBuffer attachment) {
                close(client);
            }
        });
    }

    private String mime(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    private void close(AsynchronousSocketChannel client) {
        try { client.close(); } catch (IOException ignored) {}
    }
}
