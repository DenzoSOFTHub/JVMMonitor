package it.denzosoft.jvmmonitor.protocol;

import org.junit.Test;
import static org.junit.Assert.*;

public class EventMessageTest {

    @Test
    public void testReadU8() {
        byte[] payload = new byte[]{(byte) 0xAB};
        EventMessage msg = new EventMessage(MessageType.HEARTBEAT, payload);
        assertEquals(0xAB, msg.readU8(0));
    }

    @Test
    public void testReadU16BigEndian() {
        byte[] payload = new byte[]{(byte) 0x12, (byte) 0x34};
        EventMessage msg = new EventMessage(MessageType.HEARTBEAT, payload);
        assertEquals(0x1234, msg.readU16(0));
    }

    @Test
    public void testReadU32BigEndian() {
        byte[] payload = new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};
        EventMessage msg = new EventMessage(MessageType.HEARTBEAT, payload);
        assertEquals(0xDEADBEEFL, msg.readU32(0));
    }

    @Test
    public void testReadU64BigEndian() {
        byte[] payload = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        EventMessage msg = new EventMessage(MessageType.HEARTBEAT, payload);
        assertEquals(0x0102030405060708L, msg.readU64(0));
    }

    @Test
    public void testReadI32Negative() {
        /* -12345 in big-endian */
        int val = -12345;
        byte[] payload = new byte[]{
            (byte) ((val >> 24) & 0xFF), (byte) ((val >> 16) & 0xFF),
            (byte) ((val >> 8) & 0xFF), (byte) (val & 0xFF)
        };
        EventMessage msg = new EventMessage(MessageType.HEARTBEAT, payload);
        assertEquals(-12345, msg.readI32(0));
    }

    @Test
    public void testReadString() {
        /* length-prefixed string: 0x00 0x05 "Hello" */
        byte[] payload = new byte[]{0x00, 0x05, 0x48, 0x65, 0x6C, 0x6C, 0x6F};
        EventMessage msg = new EventMessage(MessageType.HEARTBEAT, payload);
        assertEquals("Hello", msg.readString(0));
    }

    @Test
    public void testReadStringEmpty() {
        byte[] payload = new byte[]{0x00, 0x00};
        EventMessage msg = new EventMessage(MessageType.HEARTBEAT, payload);
        assertEquals("", msg.readString(0));
    }

    @Test
    public void testStringFieldLength() {
        byte[] payload = new byte[]{0x00, 0x05, 0x48, 0x65, 0x6C, 0x6C, 0x6F};
        EventMessage msg = new EventMessage(MessageType.HEARTBEAT, payload);
        assertEquals(7, msg.stringFieldLength(0)); /* 2 + 5 */
    }

    @Test
    public void testGetTimestamp() {
        byte[] payload = new byte[8];
        long ts = 1711700000000L;
        for (int i = 7; i >= 0; i--) {
            payload[i] = (byte) (ts & 0xFF);
            ts >>= 8;
        }
        EventMessage msg = new EventMessage(MessageType.HEARTBEAT, payload);
        assertEquals(1711700000000L, msg.getTimestamp());
    }

    @Test
    public void testReadAtOffset() {
        byte[] payload = new byte[12];
        /* Put u32 at offset 4 */
        payload[4] = (byte) 0xCA;
        payload[5] = (byte) 0xFE;
        payload[6] = (byte) 0xBA;
        payload[7] = (byte) 0xBE;
        EventMessage msg = new EventMessage(MessageType.HEARTBEAT, payload);
        assertEquals(0xCAFEBABEL, msg.readU32(4));
    }
}
