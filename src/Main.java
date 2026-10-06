import config.ServerConfig;
import image.ImageCatalog;
import image.ImageSource;
import server.AsyncHttpServer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Punto de entrada. Recorrido de VIEW: AsyncHttpServer -> ViewProcessor ->
 * PreparedSourceStore -> VipsViewPreparer -> ChunkWorkerPool -> ViewResponseCodec.
 * El navegador recibe el resultado en web/app.js.
 */
public final class Main {
    private static final Path CONFIG_FILE = Path.of("config", "server.properties");
    private static final Path WEB_ROOT = Path.of("web");
    private static final Path ORIGINALS_ROOT = Path.of("images", "originals");

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        // 1. Lee el puerto y solo los encabezados de images/originals; no carga los pixeles.
        ServerConfig config = ServerConfig.load(CONFIG_FILE);
        if (args.length != 0) config = config.withPort(readPort(args));
        int port = config.port();
        List<ImageSource> images = new ImageCatalog(ORIGINALS_ROOT).discover();

        // 2. Crea HTTP + WebSocket; los derivados de images/processed se eligen al pedir VIEW.
        AsyncHttpServer server = new AsyncHttpServer(config, WEB_ROOT, images);
        CountDownLatch stopped = new CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            stopped.countDown();
        }, "server-shutdown"));

        // 3. Atiende HTTP y WebSocket hasta recibir la señal de cierre.
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
