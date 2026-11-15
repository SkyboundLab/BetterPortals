import com.lauriethefish.betterportals.bukkit.entity.PortalEntityManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MobPortalTeleportationTests {


    @Test
    public void testStillPigFeetInPortalBlock() {
        assertTrue(PortalEntityManager.isInsidePortalBlocks(65.0, 0.9, y -> y == 65));
        assertFalse(PortalEntityManager.isInsidePortalBlocks(65.0, 0.9, y -> y == 64));
    }

    @Test
    public void testTallMobHeadInPortalBlock() {
        assertTrue(PortalEntityManager.isInsidePortalBlocks(65.0, 2.9, y -> y == 67));
        assertFalse(PortalEntityManager.isInsidePortalBlocks(65.0, 2.9, y -> y == 68));
    }

    @Test
    public void testEntityExactlyOneBlockTallAtBoundary() {
        assertTrue(PortalEntityManager.isInsidePortalBlocks(65.0, 1.0, y -> y == 65));
        assertFalse(PortalEntityManager.isInsidePortalBlocks(65.0, 1.0, y -> y == 66));
    }

    @Test
    public void testItemAtRestInPortalBlock() {
        assertTrue(PortalEntityManager.isInsidePortalBlocks(65.2, 0.25, y -> y == 65));
        assertFalse(PortalEntityManager.isInsidePortalBlocks(65.2, 0.25, y -> y == 66));
    }

    @Test
    public void testMobBesidePortalDoesNotCount() {
        assertFalse(PortalEntityManager.isInsidePortalBlocks(65.0, 1.8, y -> false));
    }


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


    @Test
    public void testArrowJustArrivedMovingAway() {
        assertTrue(PortalEntityManager.isMovingAwayFromPortalPlane(0.3, 3.0));
    }

    @Test
    public void testArrowApproachingPortal() {
        assertFalse(PortalEntityManager.isMovingAwayFromPortalPlane(-2.0, 3.0));
    }

    @Test
    public void testArrowLeavingOnFarSide() {
        assertTrue(PortalEntityManager.isMovingAwayFromPortalPlane(-0.3, -3.0));
    }

    @Test
    public void testStandingMobIsNotMovingAway() {
        assertFalse(PortalEntityManager.isMovingAwayFromPortalPlane(0.3, 0.0));
    }

    @Test
    public void testMobWalkingAwayFromPlane() {
        assertTrue(PortalEntityManager.isMovingAwayFromPortalPlane(0.3, 0.2));
    }

    @Test
    public void testMobWalkingBackTowardPlane() {
        assertFalse(PortalEntityManager.isMovingAwayFromPortalPlane(0.3, -0.2));
    }

    @Test
    public void testEntityExactlyOnPlaneIsNotMovingAway() {
        assertFalse(PortalEntityManager.isMovingAwayFromPortalPlane(0.0, 3.0));
    }
}
