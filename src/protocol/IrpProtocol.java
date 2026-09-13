package protocol;

import image.ImageManager;
import image.ImageMetadata;

import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class IrpProtocol {
    public static class Response {
        public final boolean binary;
        public final byte[] data;

        private Response(boolean binary, byte[] data) {
            this.binary = binary;
            this.data = data;
        }

        public static Response text(String value) {
            return new Response(false, value.getBytes(StandardCharsets.UTF_8));
        }

        public static Response binary(byte[] data) {
            return new Response(true, data);
        }
    }

    private final ImageManager imageManager;

    public IrpProtocol(ImageManager imageManager) {
        this.imageManager = imageManager;
    }

    public List<Response> handle(String message) {
        try {
            Map<String, String> fields = parse(message);
            String command = fields.getOrDefault("_command", "").toUpperCase(Locale.ROOT);

            return switch (command) {
                case "HELLO" -> List.of(Response.text("IRP/1.0 200 OK\nServer: Java Async Image Server\n"));
                case "LIST" -> List.of(Response.text(listImages()));
                case "INFO" -> List.of(Response.text(info(fields)));
                case "TILE" -> tile(fields);
                case "CLOSE" -> List.of(Response.text("IRP/1.0 200 BYE\n"));
                default -> List.of(Response.text("IRP/1.0 400 BAD REQUEST\nError: UNKNOWN_COMMAND\n"));
            };
        } catch (IllegalArgumentException e) {
            return List.of(Response.text("IRP/1.0 409 INVALID REQUEST\nError: " + e.getMessage() + "\n"));
        } catch (FileNotFoundException e) {
            return List.of(Response.text("IRP/1.0 404 NOT FOUND\nError: " + e.getMessage() + "\n"));
        } catch (Exception e) {
            e.printStackTrace();
            return List.of(Response.text("IRP/1.0 500 INTERNAL ERROR\n"));
        }
    }

    private String listImages() {
        StringBuilder sb = new StringBuilder("IRP/1.0 200 OK\n");
        List<ImageMetadata> images = imageManager.listImages();
        sb.append("Count: ").append(images.size()).append("\n");
        for (ImageMetadata m : images) {
            sb.append("Image: ").append(m.id).append("\n");
        }
        return sb.toString();
    }

    private String info(Map<String, String> fields) throws FileNotFoundException {
        String id = required(fields, "image");
        ImageMetadata m = imageManager.getMetadata(id);
        if (m == null) throw new FileNotFoundException("IMAGE_NOT_FOUND");

        return "IRP/1.0 200 OK\n"
                + "Image: " + m.id + "\n"
                + "Width: " + m.width + "\n"
                + "Height: " + m.height + "\n"
                + "Tile-Size: " + m.tileSize + "\n"
                + "Levels: " + m.levels + "\n"
                + "Source-Bytes: " + m.sourceBytes + "\n"
                + "Processor: " + m.processor + "\n";
    }

    private List<Response> tile(Map<String, String> fields) throws Exception {
        String id = required(fields, "image");
        int level = Integer.parseInt(required(fields, "level"));
        int x = Integer.parseInt(required(fields, "x"));
        int y = Integer.parseInt(required(fields, "y"));

        byte[] tile = imageManager.getTile(id, level, x, y);

        String header = "IRP/1.0 200 OK\n"
                + "Content-Type: image/jpeg\n"
                + "Content-Length: " + tile.length + "\n"
                + "Image: " + id + "\n"
                + "Level: " + level + "\n"
                + "X: " + x + "\n"
                + "Y: " + y + "\n";

        return List.of(Response.text(header), Response.binary(tile));
    }

    private Map<String, String> parse(String message) {
        String[] lines = message.replace("\r", "").split("\n");
        Map<String, String> result = new HashMap<>();

        if (lines.length == 0) throw new IllegalArgumentException("EMPTY_MESSAGE");

        String first = lines[0].trim();
        String[] firstParts = first.split("\\s+");
        if (firstParts.length != 2 || !"IRP/1.0".equalsIgnoreCase(firstParts[0])) {
            throw new IllegalArgumentException("INVALID_PROTOCOL");
        }
        result.put("_command", firstParts[1]);

        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                String key = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = lines[i].substring(colon + 1).trim();
                result.put(key, value);
            }
        }
        return result;
    }

    private String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("MISSING_" + key.toUpperCase(Locale.ROOT));
        }
        return value;
    }
}
