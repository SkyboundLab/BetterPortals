import com.lauriethefish.betterportals.bukkit.entity.PortalEntityManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the vanilla-parity mob portal rules:
 * any non-player entity standing in portal blocks teleports (no wait, no
 * plane crossing), and the activation-independent sweep runs for loaded,
 * non-cross-server portals that allow non-player teleportation.
 * These cover the pure helpers only; the live behaviour is verified by the
 * mineflayer bot tests (still pig / item / minecart, with and without a
 * player near the portal).
 */
public class MobPortalTeleportationTests {

    // --- isInsidePortalBlocks(feetY, height, isPortalBlock) ---

    @Test
    public void testStillPigFeetInPortalBlock() {
        // Pig feet at y=65.0, height 0.9: occupies only block 65.
        assertTrue(PortalEntityManager.isInsidePortalBlocks(65.0, 0.9, y -> y == 65));
        assertFalse(PortalEntityManager.isInsidePortalBlocks(65.0, 0.9, y -> y == 64));
    }

    @Test
    public void testTallMobHeadInPortalBlock() {
        // Enderman feet at y=65.0, height 2.9: occupies blocks 65..67.
        assertTrue(PortalEntityManager.isInsidePortalBlocks(65.0, 2.9, y -> y == 67));
        assertFalse(PortalEntityManager.isInsidePortalBlocks(65.0, 2.9, y -> y == 68));
    }

    @Test
    public void testEntityExactlyOneBlockTallAtBoundary() {
        // Feet exactly on a block boundary, height 1.0: occupies only the
        // block it stands in, not the one above (top is exclusive).
        assertTrue(PortalEntityManager.isInsidePortalBlocks(65.0, 1.0, y -> y == 65));
        assertFalse(PortalEntityManager.isInsidePortalBlocks(65.0, 1.0, y -> y == 66));
    }

    @Test
    public void testItemAtRestInPortalBlock() {
        // Item feet at y=65.2, height 0.25: occupies only block 65.
        assertTrue(PortalEntityManager.isInsidePortalBlocks(65.2, 0.25, y -> y == 65));
        assertFalse(PortalEntityManager.isInsidePortalBlocks(65.2, 0.25, y -> y == 66));
    }

    @Test
    public void testMobBesidePortalDoesNotCount() {
        // Feet in a solid block next to the portal, nothing above is portal.
        assertFalse(PortalEntityManager.isInsidePortalBlocks(65.0, 1.8, y -> false));
    }

    // --- shouldSweepNonPlayerTeleportation(...) ---

    @Test
    public void testSweepRunsForNormalNetherPortal() {
        assertTrue(PortalEntityManager.shouldSweepNonPlayerTeleportation(false, true, true, true));
    }

    @Test
    public void testSweepSkipsCrossServerPortals() {
        assertFalse(PortalEntityManager.shouldSweepNonPlayerTeleportation(true, true, true, true));
    }

    @Test
    public void testSweepSkipsPortalsForbiddingNonPlayers() {
        assertFalse(PortalEntityManager.shouldSweepNonPlayerTeleportation(false, false, true, true));
    }

    @Test
    public void testSweepSkipsUnloadedChunks() {
        assertFalse(PortalEntityManager.shouldSweepNonPlayerTeleportation(false, true, false, true));
    }

    @Test
    public void testSweepSkipsWhenDisabledInConfig() {
        assertFalse(PortalEntityManager.shouldSweepNonPlayerTeleportation(false, true, true, false));
    }
}
