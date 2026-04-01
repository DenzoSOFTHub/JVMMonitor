package it.denzosoft.jvmmonitor.protocol;

import java.io.IOException;
import java.io.InputStream;

public class ProtocolDecoder {

    private final InputStream in;
    private final byte[] headerBuf = new byte[ProtocolConstants.HEADER_SIZE];

    public ProtocolDecoder(InputStream in) {
        this.in = in;
    }

    public EventMessage readMessage() throws IOException {
        readFully(headerBuf, 0, ProtocolConstants.HEADER_SIZE);

        int magic = readInt(headerBuf, 0);
        if (magic != ProtocolConstants.MAGIC) {
            throw new IOException("Invalid magic: 0x" + Integer.toHexString(magic));
        }

        int version = headerBuf[4] & 0xFF;
        if (version != ProtocolConstants.VERSION) {
            throw new IOException("Unsupported protocol version: " + version);
        }

        int msgType = headerBuf[5] & 0xFF;
        int payloadLen = readInt(headerBuf, 6);

        if (payloadLen < 0 || payloadLen > ProtocolConstants.MAX_PAYLOAD) {
            throw new IOException("Invalid payload length: " + payloadLen);
        }

        byte[] payload;
        if (payloadLen > 0) {
            payload = new byte[payloadLen];
            readFully(payload, 0, payloadLen);
        } else {
            payload = new byte[0];
        }

        MessageType type = MessageType.fromCode(msgType);
        return new EventMessage(type, payload);
    }

    private void readFully(byte[] buf, int off, int len) throws IOException {
        int remaining = len;
        while (remaining > 0) {
            int read = in.read(buf, off, remaining);
            if (read < 0) {
                throw new IOException("Unexpected end of stream");
            }
            off += read;
            remaining -= read;
        }
    }

    private static int readInt(byte[] buf, int off) {
        return ((buf[off] & 0xFF) << 24)
             | ((buf[off + 1] & 0xFF) << 16)
             | ((buf[off + 2] & 0xFF) << 8)
             | (buf[off + 3] & 0xFF);
    }
}
