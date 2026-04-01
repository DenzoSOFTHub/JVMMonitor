package it.denzosoft.jvmmonitor.net;

import org.junit.Test;
import static org.junit.Assert.*;

public class AttachHelperTest {

    @Test
    public void testIsAvailableDoesNotThrow() {
        /* Should not throw, regardless of whether Attach API is available */
        boolean available = AttachHelper.isAvailable();
        /* On a JDK it should be true, on a JRE it might be false */
        /* We just verify it doesn't crash */
        assertNotNull(Boolean.valueOf(available));
    }

    @Test
    public void testGetErrorWhenNotAvailable() {
        if (!AttachHelper.isAvailable()) {
            String error = AttachHelper.getError();
            assertNotNull(error);
            assertTrue(error.length() > 0);
        }
    }

    @Test
    public void testListJvmsOnJdk() throws Exception {
        if (AttachHelper.isAvailable()) {
            java.util.List<String[]> vms = AttachHelper.listJvms();
            assertNotNull(vms);
            /* Should see at least our own JVM */
            assertTrue("Should see at least 1 JVM", vms.size() >= 1);
            for (int i = 0; i < vms.size(); i++) {
                assertNotNull(vms.get(i)[0]); /* PID */
                assertNotNull(vms.get(i)[1]); /* display name */
            }
        }
    }

    @Test(expected = Exception.class)
    public void testAttachInvalidPid() throws Exception {
        if (!AttachHelper.isAvailable()) {
            throw new Exception("Not available — skip");
        }
        /* Should fail with invalid PID */
        AttachHelper.attach("999999999", "/nonexistent/agent.so", "");
    }
}
