package it.denzosoft.jvmmonitor.agent.transport;

import it.denzosoft.jvmmonitor.agent.util.AgentLogger;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Big-endian binary protocol encoder matching the C agent's wire format.
 * Header: MAGIC(4) + VERSION(1) + MSG_TYPE(1) + PAYLOAD_LEN(4) = 10 bytes.
 */
public final class ProtocolWriter {

    public static final int MAGIC = 0x4A564D4D;
    public static final int VERSION = 1;
    public static final int HEADER_SIZE = 10;
    public static final int MAX_PAYLOAD = 8192;

    private ProtocolWriter() {}

    /** Build a complete message frame (header + payload). */
    public static byte[] buildMessage(int msgType, byte[] payload) {
        int payloadLen = payload != null ? payload.length : 0;
        byte[] frame = new byte[HEADER_SIZE + payloadLen];
        /* Magic (big-endian) */
        frame[0] = (byte) (MAGIC >>> 24);
        frame[1] = (byte) (MAGIC >>> 16);
        frame[2] = (byte) (MAGIC >>> 8);
        frame[3] = (byte) MAGIC;
        /* Version */
        frame[4] = (byte) VERSION;
        /* Message type */
        frame[5] = (byte) msgType;
        /* Payload length (big-endian) */
        frame[6] = (byte) (payloadLen >>> 24);
        frame[7] = (byte) (payloadLen >>> 16);
        frame[8] = (byte) (payloadLen >>> 8);
        frame[9] = (byte) payloadLen;
        /* Payload */
        if (payloadLen > 0) {
            System.arraycopy(payload, 0, frame, HEADER_SIZE, payloadLen);
        }
        return frame;
    }

    /** Write a UTF-8 string as u16_length + bytes. Truncates safely at UTF-8 boundary. */
    public static void writeString(DataOutputStream out, String s) throws IOException {
        if (s == null) s = "";
        byte[] bytes = s.getBytes("UTF-8");
        if (bytes.length > 65535) {
            /* Binary search for largest substring whose UTF-8 encoding fits in 65535 bytes */
            int lo = 0;
            int hi = s.length();
            while (lo < hi) {
                int mid = (lo + hi + 1) / 2;
                if (s.substring(0, mid).getBytes("UTF-8").length <= 65535) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            bytes = s.substring(0, lo).getBytes("UTF-8");
        }
        out.writeShort(bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    /** Create a new payload builder. */
    public static PayloadBuilder payload() {
        return new PayloadBuilder();
    }

    /**
     * Convenience builder for constructing payloads.
     * Uses DataOutputStream over ByteArrayOutputStream for big-endian encoding.
     */
    public static final class PayloadBuilder {
        private final ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
        private final DataOutputStream dos = new DataOutputStream(baos);

        public PayloadBuilder writeU8(int v) throws IOException {
            dos.writeByte(v);
            return this;
        }

        public PayloadBuilder writeU16(int v) throws IOException {
            dos.writeShort(v);
            return this;
        }

        public PayloadBuilder writeU32(long v) throws IOException {
            dos.writeInt((int) v);
            return this;
        }

        public PayloadBuilder writeU64(long v) throws IOException {
            dos.writeLong(v);
            return this;
        }

        public PayloadBuilder writeI32(int v) throws IOException {
            dos.writeInt(v);
            return this;
        }

        public PayloadBuilder writeI64(long v) throws IOException {
            dos.writeLong(v);
            return this;
        }

        public PayloadBuilder writeString(String s) throws IOException {
            ProtocolWriter.writeString(dos, s);
            return this;
        }

        public PayloadBuilder writeDouble(double v) throws IOException {
            dos.writeDouble(v);
            return this;
        }

        public byte[] build() throws IOException {
            dos.flush();
            byte[] data = baos.toByteArray();
            if (data.length > MAX_PAYLOAD) {
                AgentLogger.debug("Payload exceeds MAX_PAYLOAD: " + data.length + " > " + MAX_PAYLOAD
                        + " — message may be rejected by collector");
            }
            return data;
        }

        /** Build and wrap with header as a complete message frame. */
        public byte[] buildMessage(int msgType) throws IOException {
            return ProtocolWriter.buildMessage(msgType, build());
        }
    }
}
