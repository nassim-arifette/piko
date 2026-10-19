package app.piko.tor;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProtectionStateTest {
    @Test public void engineNeverStartsWithoutEveryPrerequisite() {
        for (int bits = 0; bits < 512; bits++) {
            boolean requested = (bits & 1) != 0, target = (bits & 2) != 0, tun = (bits & 4) != 0;
            boolean alwaysOn = (bits & 8) != 0, lockdown = (bits & 16) != 0;
            boolean privateDns = (bits & 32) != 0, failed = (bits & 64) != 0, ready = (bits & 128) != 0;
            boolean network = (bits & 256) != 0;
            ProtectionState state = ProtectionState.evaluate(requested, target, tun, alwaysOn,
                    lockdown, privateDns, failed, ready, network);
            assertEquals(requested && target && tun && alwaysOn && lockdown && !privateDns && !failed && network,
                    state.permitsEngine());
            if (!requested) assertEquals(ProtectionState.OFF, state);
            if (state == ProtectionState.TOR_READY) assertTrue(ready && state.permitsEngine());
        }
    }

    @Test public void staleBootstrapDoesNotHideRevocation() {
        assertEquals(ProtectionState.VPN_MISSING,
                ProtectionState.evaluate(true, true, false, true, true, false, false, true, true));
        assertEquals(ProtectionState.LOCKDOWN_MISSING,
                ProtectionState.evaluate(true, true, true, true, false, false, false, true, true));
        assertEquals(ProtectionState.TARGET_INVALID,
                ProtectionState.evaluate(true, false, true, true, true, false, false, true, true));
    }

    @Test public void engineFailureDoesNotBecomeReady() {
        assertEquals(ProtectionState.TOR_UNAVAILABLE,
                ProtectionState.evaluate(true, true, true, true, true, false, true, true, true));
    }
}
