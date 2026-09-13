package image;

/** Utilidad de preparación sin iniciar el servidor HTTP. */
public class PrepareImages {
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        int tileSize = args.length >= 1 ? Integer.parseInt(args[0]) : 512;
        ImageManager manager = new ImageManager("images/originals", "images/processed", tileSize);
        System.out.println("ImageMagick: " + (manager.isImageMagickAvailable() ? "DISPONIBLE" : "NO ENCONTRADO"));
        manager.processAllImages();
        System.out.println("Preparación terminada.");
    }
}
