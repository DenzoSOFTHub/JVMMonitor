package it.denzosoft.jvmmonitor.protocol;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class ProtocolDecoderTest {

    private byte[] buildMessage(int msgType, byte[] payload) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(ProtocolConstants.MAGIC);
        dos.writeByte(ProtocolConstants.VERSION);
        dos.writeByte(msgType);
        dos.writeInt(payload.length);
        dos.write(payload);
        dos.flush();
        return baos.toByteArray();
    }

    @Test
    public void testDecodeHeartbeat() throws IOException {
        byte[] payload = new byte[8];
        /* timestamp */
        long ts = System.currentTimeMillis();
        for (int i = 7; i >= 0; i--) {
            payload[i] = (byte) (ts & 0xFF);
            ts >>= 8;
        }
        byte[] wire = buildMessage(ProtocolConstants.MSG_HEARTBEAT, payload);
        ProtocolDecoder decoder = new ProtocolDecoder(new ByteArrayInputStream(wire));
        EventMessage msg = decoder.readMessage();
        assertEquals(MessageType.HEARTBEAT, msg.getType());
        assertEquals(8, msg.getPayloadLength());
    }

    @Test
    public void testDecodeEmptyPayload() throws IOException {
        byte[] wire = buildMessage(ProtocolConstants.MSG_HEARTBEAT, new byte[0]);
        ProtocolDecoder decoder = new ProtocolDecoder(new ByteArrayInputStream(wire));
        EventMessage msg = decoder.readMessage();
        assertEquals(MessageType.HEARTBEAT, msg.getType());
        assertEquals(0, msg.getPayloadLength());
    }

    @Test
    public void testDecodeMultipleMessages() throws IOException {
        ByteArrayOutputStream combined = new ByteArrayOutputStream();
        combined.write(buildMessage(ProtocolConstants.MSG_HEARTBEAT, new byte[]{0x01}));
        combined.write(buildMessage(ProtocolConstants.MSG_GC_EVENT, new byte[]{0x02, 0x03}));
        combined.write(buildMessage(ProtocolConstants.MSG_ALARM, new byte[]{0x04}));

        ProtocolDecoder decoder = new ProtocolDecoder(
                new ByteArrayInputStream(combined.toByteArray()));

        EventMessage m1 = decoder.readMessage();
        assertEquals(MessageType.HEARTBEAT, m1.getType());
        assertEquals(1, m1.getPayloadLength());

        EventMessage m2 = decoder.readMessage();
        assertEquals(MessageType.GC_EVENT, m2.getType());
        assertEquals(2, m2.getPayloadLength());

        EventMessage m3 = decoder.readMessage();
        assertEquals(MessageType.ALARM, m3.getType());
        assertEquals(1, m3.getPayloadLength());
    }

    @Test(expected = IOException.class)
    public void testDecodeBadMagic() throws IOException {
        byte[] wire = buildMessage(ProtocolConstants.MSG_HEARTBEAT, new byte[0]);
        wire[0] = (byte) 0xFF; /* corrupt magic */
        new ProtocolDecoder(new ByteArrayInputStream(wire)).readMessage();
    }

    @Test(expected = IOException.class)
    public void testDecodeBadVersion() throws IOException {
        byte[] wire = buildMessage(ProtocolConstants.MSG_HEARTBEAT, new byte[0]);
        wire[4] = (byte) 99; /* wrong version */
        new ProtocolDecoder(new ByteArrayInputStream(wire)).readMessage();
    }

    @Test(expected = IOException.class)
    public void testDecodeTruncatedStream() throws IOException {
        byte[] wire = buildMessage(ProtocolConstants.MSG_HEARTBEAT, new byte[100]);
        /* Truncate: only send header + partial payload */
        byte[] truncated = new byte[ProtocolConstants.HEADER_SIZE + 10];
        System.arraycopy(wire, 0, truncated, 0, truncated.length);
        new ProtocolDecoder(new ByteArrayInputStream(truncated)).readMessage();
    }

    @Test
    public void testDecodeGcEventPayload() throws IOException {
        ByteArrayOutputStream payloadBaos = new ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(payloadBaos);
        p.writeLong(1711700000000L);  /* timestamp */
        p.writeByte(3);               /* gc_type = full */
        p.writeLong(150000000L);      /* duration nanos */
        p.writeInt(42);               /* gc count */
        p.writeInt(5);                /* full gc count */
        p.flush();

        byte[] wire = buildMessage(ProtocolConstants.MSG_GC_EVENT, payloadBaos.toByteArray());
        ProtocolDecoder decoder = new ProtocolDecoder(new ByteArrayInputStream(wire));
        EventMessage msg = decoder.readMessage();

        assertEquals(MessageType.GC_EVENT, msg.getType());
        assertEquals(1711700000000L, msg.readU64(0));
        assertEquals(3, msg.readU8(8));
        assertEquals(150000000L, msg.readU64(9));
        assertEquals(42, msg.readI32(17));
        assertEquals(5, msg.readI32(21));
    }
}
