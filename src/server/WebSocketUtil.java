package server;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

public final class WebSocketUtil {
    private WebSocketUtil() {}

    public static String acceptKey(String key) throws Exception {
        String magic = key.trim() + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        return Base64.getEncoder().encodeToString(
                sha1.digest(magic.getBytes(StandardCharsets.ISO_8859_1))
        );
    }

    public static ByteBuffer frame(byte opcode, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x80 | (opcode & 0x0F));
        int len = payload.length;

        if (len <= 125) {
            out.write(len);
        } else if (len <= 65535) {
            out.write(126);
            out.write((len >>> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(127);
            long longLength = Integer.toUnsignedLong(len);
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((longLength >>> (8 * i)) & 0xFF));
            }
        }
        out.writeBytes(payload);
        return ByteBuffer.wrap(out.toByteArray());
    }

    public static DecodedFrame tryDecode(ByteBuffer buffer) {
        buffer.flip();
        try {
            if (buffer.remaining() < 2) return null;

            int b1 = buffer.get() & 0xFF;
            int b2 = buffer.get() & 0xFF;
            boolean finalFrame = (b1 & 0x80) != 0;
            if ((b1 & 0x70) != 0) {
                throw new IllegalArgumentException("Los bits RSV del frame deben ser cero");
            }
            boolean masked = (b2 & 0x80) != 0;
            long len = b2 & 0x7F;

            if (len == 126) {
                if (buffer.remaining() < 2) return null;
                len = buffer.getShort() & 0xFFFF;
            } else if (len == 127) {
                if (buffer.remaining() < 8) return null;
                len = buffer.getLong();
                if (len < 0) {
                    throw new IllegalArgumentException("Longitud WebSocket invalida");
                }
            }

            byte[] mask = null;
            if (masked) {
                if (buffer.remaining() < 4) return null;
                mask = new byte[4];
                buffer.get(mask);
            }

            if (len > Integer.MAX_VALUE || buffer.remaining() < len) return null;
            byte[] payload = new byte[(int) len];
            buffer.get(payload);

            if (masked) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i % 4];
                }
            }

            int consumed = buffer.position();
            ByteBuffer rest = ByteBuffer.allocate(Math.max(8192, buffer.capacity()));
            while (buffer.hasRemaining()) rest.put(buffer.get());

            buffer.clear();
            rest.flip();
            buffer.put(rest);

            return new DecodedFrame(
                    (byte) (b1 & 0x0F),
                    payload,
                    consumed,
                    finalFrame,
                    masked
            );
        } finally {
            if (buffer.limit() != buffer.capacity()) {
                buffer.position(buffer.limit());
                buffer.limit(buffer.capacity());
            }
        }
    }

    public record DecodedFrame(
            byte opcode,
            byte[] payload,
            int consumed,
            boolean finalFrame,
            boolean masked
    ) {}
}
