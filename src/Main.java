import image.ImageManager;
import server.AsyncHttpServer;
import java.util.concurrent.CountDownLatch;

public class Main {
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        int port = 8080;
        if (args.length >= 2 && "-port".equals(args[0])) {
            port = Integer.parseInt(args[1]);
        }

        ImageManager imageManager = new ImageManager(
                "images/originals",
                "images/processed",
                512
        );

        System.out.println("Preparando imágenes disponibles...");
        System.out.println("ImageMagick para PSB/TIFF gigante: " + (imageManager.isImageMagickAvailable() ? "DISPONIBLE" : "NO ENCONTRADO"));
        imageManager.processAllImages();

        AsyncHttpServer server = new AsyncHttpServer(port, "web", imageManager);
        server.start();

        System.out.println("Servidor iniciado en http://localhost:" + port);
        System.out.println("WebSocket IRP disponible en ws://localhost:" + port + "/irp");
        System.out.println("Servidor listo. Use Ctrl+C para detenerlo.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { server.stop(); } catch (Exception ignored) {}
        }));

        new CountDownLatch(1).await();
    }
}
