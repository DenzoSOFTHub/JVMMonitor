package it.denzosoft.jvmmonitor.protocol;

import org.junit.Test;
import static org.junit.Assert.*;

public class MessageTypeTest {

    @Test
    public void testFromCodeKnownTypes() {
        assertEquals(MessageType.HANDSHAKE, MessageType.fromCode(0x01));
        assertEquals(MessageType.CPU_SAMPLE, MessageType.fromCode(0x10));
        assertEquals(MessageType.GC_EVENT, MessageType.fromCode(0x20));
        assertEquals(MessageType.MEMORY_SNAPSHOT, MessageType.fromCode(0x40));
        assertEquals(MessageType.ALARM, MessageType.fromCode(0x60));
    }

    @Test
    public void testFromCodeUnknown() {
        assertEquals(MessageType.UNKNOWN, MessageType.fromCode(0xFF));
        assertEquals(MessageType.UNKNOWN, MessageType.fromCode(-1));
    }

    @Test
    public void testGetCode() {
        assertEquals(0x01, MessageType.HANDSHAKE.getCode());
        assertEquals(0x10, MessageType.CPU_SAMPLE.getCode());
        assertEquals(0x20, MessageType.GC_EVENT.getCode());
    }

    @Test
    public void testRoundtrip() {
        for (MessageType mt : MessageType.values()) {
            if (mt != MessageType.UNKNOWN) {
                assertEquals(mt, MessageType.fromCode(mt.getCode()));
            }
        }
    }
}
