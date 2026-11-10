import com.lauriethefish.betterportals.bukkit.entity.PortalEntityManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the vanilla-parity mob portal rules:
 * any non-player entity standing in portal blocks teleports (no wait, no
 * plane crossing), and the activation-independent sweep runs for loaded,
 * non-cross-server portals that allow non-player teleportation.
 * The dwell check never fires for an entity moving away from the portal
 * plane, so a projectile that just arrived at the destination is not
 * bounced straight back (2026-11-09 crossbow arrow stuck report).
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

    // --- isMovingAwayFromPortalPlane(signedDistance, velocityAlongNormal) ---

    @Test
    public void testArrowJustArrivedMovingAway() {
        // Crossbow arrow teleported to the destination, 0.3 past the plane,
        // still flying away at 3 blocks/tick: moving away, so the dwell
        // check must NOT bounce it back (2026-11-09 arrow stuck report).
        assertTrue(PortalEntityManager.isMovingAwayFromPortalPlane(0.3, 3.0));
    }

    @Test
    public void testArrowApproachingPortal() {
        // Arrow 2 blocks before the plane, flying toward it: not moving
        // away, so the dwell check may teleport it through.
        assertFalse(PortalEntityManager.isMovingAwayFromPortalPlane(-2.0, 3.0));
    }

    @Test
    public void testArrowLeavingOnFarSide() {
        // Same as arriving, but on the - side of the plane, flying further -.
        assertTrue(PortalEntityManager.isMovingAwayFromPortalPlane(-0.3, -3.0));
    }

    @Test
    public void testStandingMobIsNotMovingAway() {
        // Mob at rest in the portal blocks: velocity 0, so the dwell check
        // still fires and the farm behaviour is unchanged.
        assertFalse(PortalEntityManager.isMovingAwayFromPortalPlane(0.3, 0.0));
    }

    @Test
    public void testMobWalkingAwayFromPlane() {
        // Mob that just arrived and walks away from the plane: not bounced
        // back, it keeps walking (vanilla: only walking back in re-enters).
        assertTrue(PortalEntityManager.isMovingAwayFromPortalPlane(0.3, 0.2));
    }

    @Test
    public void testMobWalkingBackTowardPlane() {
        // Mob on the + side walking back toward the plane: not moving away,
        // so the dwell check teleports it back through.
        assertFalse(PortalEntityManager.isMovingAwayFromPortalPlane(0.3, -0.2));
    }

    @Test
    public void testEntityExactlyOnPlaneIsNotMovingAway() {
        // On the plane itself the signed distance is 0, so it is never
        // moving away however fast it goes: the dwell check may fire.
        assertFalse(PortalEntityManager.isMovingAwayFromPortalPlane(0.0, 3.0));
    }
}
