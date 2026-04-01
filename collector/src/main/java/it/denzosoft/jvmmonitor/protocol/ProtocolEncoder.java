package it.denzosoft.jvmmonitor.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public final class ProtocolEncoder {

    private ProtocolEncoder() {}

    public static void sendCommand(OutputStream out, int cmdSubtype, byte[] payload)
            throws IOException {
        int payloadLen = 1 + (payload != null ? payload.length : 0);
        byte[] header = new byte[ProtocolConstants.HEADER_SIZE];
        writeInt(header, 0, ProtocolConstants.MAGIC);
        header[4] = (byte) ProtocolConstants.VERSION;
        header[5] = (byte) ProtocolConstants.MSG_COMMAND;
        writeInt(header, 6, payloadLen);

        synchronized (out) {
            out.write(header);
            out.write(cmdSubtype);
            if (payload != null && payload.length > 0) {
                out.write(payload);
            }
            out.flush();
        }
    }

    public static byte[] encodeEnableModule(String name, int level,
                                             String target, int durationSec) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(128);
        DataOutputStream dos = new DataOutputStream(baos);
        try {
            byte[] nameBytes = name.getBytes("UTF-8");
            dos.writeShort(nameBytes.length);
            dos.write(nameBytes);
            dos.writeByte(level);
            dos.writeInt(durationSec);
            if (target != null && target.length() > 0) {
                byte[] targetBytes = target.getBytes("UTF-8");
                dos.writeShort(targetBytes.length);
                dos.write(targetBytes);
            } else {
                dos.writeShort(0);
            }
            dos.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return baos.toByteArray();
    }

    public static byte[] encodeDisableModule(String name) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(64);
        DataOutputStream dos = new DataOutputStream(baos);
        try {
            byte[] nameBytes = name.getBytes("UTF-8");
            dos.writeShort(nameBytes.length);
            dos.write(nameBytes);
            dos.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return baos.toByteArray();
    }

    public static byte[] encodeListModules() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(16);
        DataOutputStream dos = new DataOutputStream(baos);
        try {
            dos.writeShort(0); /* empty name = list all */
            dos.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return baos.toByteArray();
    }

    private static void writeInt(byte[] buf, int off, int val) {
        buf[off]     = (byte) ((val >> 24) & 0xFF);
        buf[off + 1] = (byte) ((val >> 16) & 0xFF);
        buf[off + 2] = (byte) ((val >> 8) & 0xFF);
        buf[off + 3] = (byte) (val & 0xFF);
    }
}
