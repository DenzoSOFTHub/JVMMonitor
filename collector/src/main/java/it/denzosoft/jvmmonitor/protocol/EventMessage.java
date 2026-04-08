package it.denzosoft.jvmmonitor.protocol;

import java.io.UnsupportedEncodingException;

public final class EventMessage {

    private final MessageType type;
    private final byte[] payload;

    public EventMessage(MessageType type, byte[] payload) {
        this.type = type;
        this.payload = payload;
    }

    public MessageType getType() {
        return type;
    }

    public byte[] getPayload() {
        return payload;
    }

    public int getPayloadLength() {
        return payload != null ? payload.length : 0;
    }

    /* Big-endian read helpers — all methods validate bounds before access. */

    public int readU8(int offset) {
        if (payload == null || offset < 0 || offset >= payload.length) return 0;
        return payload[offset] & 0xFF;
    }

    public int readU16(int offset) {
        if (payload == null || offset < 0 || offset + 2 > payload.length) return 0;
        return ((payload[offset] & 0xFF) << 8)
             | (payload[offset + 1] & 0xFF);
    }

    public long readU32(int offset) {
        if (payload == null || offset < 0 || offset + 4 > payload.length) return 0;
        return ((long)(payload[offset] & 0xFF) << 24)
             | ((long)(payload[offset + 1] & 0xFF) << 16)
             | ((long)(payload[offset + 2] & 0xFF) << 8)
             | (long)(payload[offset + 3] & 0xFF);
    }

    public long readU64(int offset) {
        if (payload == null || offset < 0 || offset + 8 > payload.length) return 0;
        return ((long)(payload[offset] & 0xFF) << 56)
             | ((long)(payload[offset + 1] & 0xFF) << 48)
             | ((long)(payload[offset + 2] & 0xFF) << 40)
             | ((long)(payload[offset + 3] & 0xFF) << 32)
             | ((long)(payload[offset + 4] & 0xFF) << 24)
             | ((long)(payload[offset + 5] & 0xFF) << 16)
             | ((long)(payload[offset + 6] & 0xFF) << 8)
             | (long)(payload[offset + 7] & 0xFF);
    }

    public int readI32(int offset) {
        return (int) readU32(offset);
    }

    public long readI64(int offset) {
        return readU64(offset);
    }

    public String readString(int offset) {
        if (payload == null || offset < 0 || offset + 2 > payload.length) return "";
        int len = readU16(offset);
        if (len == 0) return "";
        if (offset + 2 + len > payload.length) {
            /* Truncated string — read what we can */
            len = payload.length - offset - 2;
            if (len <= 0) return "";
        }
        try {
            return new String(payload, offset + 2, len, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return new String(payload, offset + 2, len);
        }
    }

    public int stringFieldLength(int offset) {
        if (payload == null || offset < 0 || offset + 2 > payload.length) return 2;
        int len = readU16(offset);
        /* Clamp to available payload to prevent callers from advancing past end */
        if (offset + 2 + len > payload.length) {
            return payload.length - offset;
        }
        return 2 + len;
    }

    public long getTimestamp() {
        if (payload != null && payload.length >= 8) {
            return readU64(0);
        }
        return 0;
    }
}
