package config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

/** Opciones del proceso; los tamanos y opcodes de PAI/1 no son configurables. */
public record ServerConfig(int port, int chunkSize, int serverThreads, int viewWorkers,
                           long cacheBytes, long writeQueueBytes, int normalJpegQuality) {
    private static final long MIB = 1024L * 1024L;
    private static final Set<String> KEYS = Set.of("port", "chunk.size", "server.threads",
            "view.workers", "cache.maxMiB", "writeQueue.maxMiB",
            "jpeg.normalQuality");

    public ServerConfig {
        check("port", port, 1, 65535);
        check("chunk.size", chunkSize, 128, 1024);
        check("server.threads", serverThreads, 1, 32);
        check("view.workers", viewWorkers, 1, 32);
        check("cache.maxMiB", cacheBytes / MIB, 1, 4096);
        check("writeQueue.maxMiB", writeQueueBytes / MIB, 16, 4096);
        if (cacheBytes % MIB != 0 || writeQueueBytes % MIB != 0) {
            throw new IllegalArgumentException("Los limites de memoria deben usar MiB enteros");
        }
        check("jpeg.normalQuality", normalJpegQuality, 1, 100);
    }

    public static ServerConfig load(Path file) throws IOException {
        Properties values = new Properties();
        try (var input = Files.newInputStream(file)) {
            values.load(input);
        }
        for (String key : values.stringPropertyNames()) {
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("Opcion desconocida en " + file + ": " + key);
            }
        }
        for (String key : KEYS) {
            if (!values.containsKey(key)) {
                throw new IllegalArgumentException("Falta " + key + " en " + file);
            }
        }
        return new ServerConfig(
                number(values, "port", 1, 65535),
                number(values, "chunk.size", 128, 1024),
                number(values, "server.threads", 1, 32),
                number(values, "view.workers", 1, 32),
                number(values, "cache.maxMiB", 1, 4096) * MIB,
                number(values, "writeQueue.maxMiB", 16, 4096) * MIB,
                number(values, "jpeg.normalQuality", 1, 100)
        );
    }

    public ServerConfig withPort(int replacement) {
        return new ServerConfig(replacement, chunkSize, serverThreads, viewWorkers,
                cacheBytes, writeQueueBytes, normalJpegQuality);
    }

    private static int number(Properties values, String key, int minimum, int maximum) {
        final int parsed;
        try {
            parsed = Integer.parseInt(values.getProperty(key).trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(key + " debe ser un entero", error);
        }
        check(key, parsed, minimum, maximum);
        return parsed;
    }

    private static void check(String name, long value, long minimum, long maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " debe estar entre " + minimum
                    + " y " + maximum);
        }
    }
}
