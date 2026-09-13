package image;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ImageMetadata {
    public final String id;
    public final int width;
    public final int height;
    public final int tileSize;
    public final int levels;
    public final String sourceFile;
    public final long sourceBytes;
    public final long sourceLastModified;
    public final String processor;

    public ImageMetadata(String id, int width, int height, int tileSize, int levels) {
        this(id, width, height, tileSize, levels, "", 0L, 0L, "java-imageio");
    }

    public ImageMetadata(String id, int width, int height, int tileSize, int levels,
                         String sourceFile, long sourceBytes, long sourceLastModified,
                         String processor) {
        this.id = id;
        this.width = width;
        this.height = height;
        this.tileSize = tileSize;
        this.levels = levels;
        this.sourceFile = sourceFile == null ? "" : sourceFile;
        this.sourceBytes = sourceBytes;
        this.sourceLastModified = sourceLastModified;
        this.processor = processor == null ? "unknown" : processor;
    }

    public String toJson() {
        return "{\n"
                + "  \"id\":\"" + escape(id) + "\",\n"
                + "  \"width\":" + width + ",\n"
                + "  \"height\":" + height + ",\n"
                + "  \"tileSize\":" + tileSize + ",\n"
                + "  \"levels\":" + levels + ",\n"
                + "  \"sourceFile\":\"" + escape(sourceFile) + "\",\n"
                + "  \"sourceBytes\":" + sourceBytes + ",\n"
                + "  \"sourceLastModified\":" + sourceLastModified + ",\n"
                + "  \"processor\":\"" + escape(processor) + "\"\n"
                + "}\n";
    }

    public static ImageMetadata fromJson(String json) {
        return new ImageMetadata(
                stringValue(json, "id"),
                intValue(json, "width"),
                intValue(json, "height"),
                intValue(json, "tileSize"),
                intValue(json, "levels"),
                stringValue(json, "sourceFile"),
                longValue(json, "sourceBytes"),
                longValue(json, "sourceLastModified"),
                stringValue(json, "processor")
        );
    }

    private static String stringValue(String json, String key) {
        Matcher m = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").matcher(json);
        return m.find() ? unescape(m.group(1)) : "";
    }

    private static int intValue(String json, String key) {
        return (int) longValue(json, key);
    }

    private static long longValue(String json, String key) {
        Matcher m = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
