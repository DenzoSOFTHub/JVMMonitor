package it.denzosoft.jvmmonitor.protocol;

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class ProtocolEncoderTest {

    @Test
    public void testEncodeEnableModule() {
        byte[] data = ProtocolEncoder.encodeEnableModule("alloc", 2, "com.app.Foo", 300);
        assertNotNull(data);
        assertTrue(data.length > 0);

        /* Decode: u16 name_len + name + u8 level + u32 duration + u16 target_len + target */
        EventMessage msg = new EventMessage(MessageType.COMMAND, data);
        int nameLen = msg.readU16(0);
        assertEquals(5, nameLen);
        assertEquals("alloc", msg.readString(0));
    }

    @Test
    public void testEncodeDisableModule() {
        byte[] data = ProtocolEncoder.encodeDisableModule("locks");
        assertNotNull(data);

        EventMessage msg = new EventMessage(MessageType.COMMAND, data);
        assertEquals("locks", msg.readString(0));
    }

    @Test
    public void testSendCommandWritesValidMessage() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ProtocolEncoder.sendCommand(baos, ProtocolConstants.CMD_ENABLE_MODULE, new byte[]{0x01, 0x02});

        byte[] wire = baos.toByteArray();
        assertTrue(wire.length >= ProtocolConstants.HEADER_SIZE + 3);

        ProtocolDecoder decoder = new ProtocolDecoder(new ByteArrayInputStream(wire));
        EventMessage msg = decoder.readMessage();
        assertEquals(MessageType.COMMAND, msg.getType());
        assertEquals(3, msg.getPayloadLength()); /* 1 (subtype) + 2 (data) */
        assertEquals(ProtocolConstants.CMD_ENABLE_MODULE, msg.readU8(0));
    }

    @Test
    public void testEncodeEnableModuleNoTarget() {
        byte[] data = ProtocolEncoder.encodeEnableModule("memory", 1, null, 60);
        assertNotNull(data);

        EventMessage msg = new EventMessage(MessageType.COMMAND, data);
        String name = msg.readString(0);
        assertEquals("memory", name);
    }
}
