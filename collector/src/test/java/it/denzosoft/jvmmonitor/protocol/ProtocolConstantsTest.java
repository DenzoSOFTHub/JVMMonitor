package it.denzosoft.jvmmonitor.protocol;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProtocolConstantsTest {

    @Test
    public void testMagicIsFourBytes() {
        byte[] buf = new byte[4];
        buf[0] = (byte) ((ProtocolConstants.MAGIC >> 24) & 0xFF);
        buf[1] = (byte) ((ProtocolConstants.MAGIC >> 16) & 0xFF);
        buf[2] = (byte) ((ProtocolConstants.MAGIC >> 8) & 0xFF);
        buf[3] = (byte) (ProtocolConstants.MAGIC & 0xFF);
        assertEquals("JVMM", new String(buf));
    }

    @Test
    public void testHeaderSize() {
        assertEquals(10, ProtocolConstants.HEADER_SIZE);
    }

    @Test
    public void testAllMessageTypesUnique() {
        int[] types = {
            ProtocolConstants.MSG_HANDSHAKE, ProtocolConstants.MSG_HEARTBEAT,
            ProtocolConstants.MSG_COMMAND, ProtocolConstants.MSG_COMMAND_RESP,
            ProtocolConstants.MSG_CPU_SAMPLE, ProtocolConstants.MSG_GC_EVENT,
            ProtocolConstants.MSG_THREAD_SNAPSHOT, ProtocolConstants.MSG_THREAD_EVENT,
            ProtocolConstants.MSG_MEMORY_SNAPSHOT, ProtocolConstants.MSG_CLASS_INFO,
            ProtocolConstants.MSG_ALARM, ProtocolConstants.MSG_MODULE_EVENT,
            ProtocolConstants.MSG_ALLOC_SAMPLE, ProtocolConstants.MSG_MONITOR_EVENT,
            ProtocolConstants.MSG_METHOD_INFO
        };
        for (int i = 0; i < types.length; i++) {
            for (int j = i + 1; j < types.length; j++) {
                assertNotEquals("Duplicate type at indices " + i + " and " + j,
                        types[i], types[j]);
            }
        }
    }
}
