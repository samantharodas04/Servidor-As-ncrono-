import image.ImageCatalog;
import image.ImageSource;
import server.AsyncHttpServer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** Inicia el servidor HTTP/WebSocket del proyecto. */
public final class Main {
    private static final int DEFAULT_PORT = 8080;
    private static final Path WEB_ROOT = Path.of("web");
    private static final Path ORIGINALS_ROOT = Path.of("images", "originals");

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        int port = readPort(args);
        List<ImageSource> images = new ImageCatalog(ORIGINALS_ROOT).discover();
        AsyncHttpServer server = new AsyncHttpServer(port, WEB_ROOT, images);
        CountDownLatch stopped = new CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            stopped.countDown();
        }, "server-shutdown"));

        server.start();
        System.out.println("Servidor HTTP/WebSocket iniciado");
        System.out.println("Pagina: http://localhost:" + port + "/");
        System.out.println("WebSocket: ws://localhost:" + port + "/pai");
        System.out.println("Imagenes disponibles: " + images.size());
        System.out.println("Hito actual: VIEW_START, CHUNK y VIEW_END se envian al navegador.");

        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.close();
        }
    }

    private static int readPort(String[] args) {
        if (args.length == 0) {
            return DEFAULT_PORT;
        }
        if (args.length != 2 || !"-port".equals(args[0])) {
            throw new IllegalArgumentException("Uso: java Main [-port PUERTO]");
        }

        final int port;
        try {
            port = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("El puerto debe ser un numero entero", e);
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("El puerto debe estar entre 1 y 65535");
        }
        return port;
    }
}
