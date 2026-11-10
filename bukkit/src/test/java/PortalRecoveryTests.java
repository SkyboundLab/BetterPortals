import com.lauriethefish.betterportals.api.PortalDirection;
import com.lauriethefish.betterportals.bukkit.portal.spawning.PortalRecovery;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for portal recovery window detection
 * ({@link PortalRecovery#findPortalWindow}): given a single portal block,
 * the rectangular window containing it is found (direction, size and
 * bottom-left frame block), so a portal that exists as blocks but is not
 * registered can be re-registered without the player rebuilding it.
 * These cover the pure detection only; the live recovery is verified by
 * starting a server with an unregistered portal and walking a player in.
 */
public class PortalRecoveryTests {

    /** Builds a PortalBlockTest for a rectangular window of portal blocks. */
    private static PortalRecovery.PortalBlockTest window(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        Set<String> blocks = new HashSet<>();
        for(int x = minX; x <= maxX; x++) {
            for(int y = minY; y <= maxY; y++) {
                for(int z = minZ; z <= maxZ; z++) {
                    blocks.add(x + "," + y + "," + z);
                }
            }
        }
        return (x, y, z) -> blocks.contains(x + "," + y + "," + z);
    }

    private static PortalRecovery.PortalWindow find(int x, int y, int z, PortalRecovery.PortalBlockTest test) {
        return PortalRecovery.findPortalWindow(new Location(null, x, y, z), test);
    }

    @Test
    public void testTwoByThreeNorthPortal() {
        // Portal blocks x=120..121, y=65..67, z=120 (extends in X: NORTH).
        PortalRecovery.PortalWindow window = find(120, 66, 120, window(120, 65, 120, 121, 67, 120));
        assertNotNull(window);
        assertEquals(PortalDirection.NORTH, window.direction);
        assertEquals(new Vector(2, 3, 0), window.size);
        // Bottom-left frame block: one left and one down from the lowest portal block.
        assertEquals(new Vector(119, 64, 120), window.bottomLeftFrame);
    }

    @Test
    public void testThreeByFourEastPortal() {
        // Portal blocks x=50, y=70..73, z=200..202 (extends in Z: EAST).
        PortalRecovery.PortalWindow window = find(50, 71, 201, window(50, 70, 200, 50, 73, 202));
        assertNotNull(window);
        assertEquals(PortalDirection.EAST, window.direction);
        assertEquals(new Vector(3, 4, 0), window.size);
        assertEquals(new Vector(50, 69, 199), window.bottomLeftFrame);
    }

    @Test
    public void testAnyBlockInWindowFindsSameWindow() {
        // Starting from the top-right corner still finds the whole window.
        PortalRecovery.PortalWindow window = find(121, 67, 120, window(120, 65, 120, 121, 67, 120));
        assertNotNull(window);
        assertEquals(PortalDirection.NORTH, window.direction);
        assertEquals(new Vector(2, 3, 0), window.size);
        assertEquals(new Vector(119, 64, 120), window.bottomLeftFrame);
    }

    @Test
    public void testLargerWindow() {
        // 4 wide, 5 high, NORTH.
        PortalRecovery.PortalWindow window = find(10, 62, 30, window(10, 60, 30, 13, 64, 30));
        assertNotNull(window);
        assertEquals(PortalDirection.NORTH, window.direction);
        assertEquals(new Vector(4, 5, 0), window.size);
        assertEquals(new Vector(9, 59, 30), window.bottomLeftFrame);
    }

    @Test
    public void testLonePortalBlockIsNotAWindow() {
        assertNull(find(120, 65, 120, window(120, 65, 120, 120, 65, 120)));
    }

    @Test
    public void testNonRectangularWindowIsRejected() {
        // 2x3 window with the middle block missing is not a valid portal.
        Set<String> blocks = new HashSet<>();
        for(int x = 120; x <= 121; x++) {
            for(int y = 65; y <= 67; y++) {
                if(x == 121 && y == 66) { continue; }
                blocks.add(x + "," + y + "," + 120);
            }
        }
        assertNull(find(120, 66, 120, (x, y, z) -> blocks.contains(x + "," + y + "," + z)));
    }

    @Test
    public void testTooSmallWindowIsRejected() {
        // 2 wide but only 2 high: below the 2x3 minimum.
        assertNull(find(120, 65, 120, window(120, 65, 120, 121, 66, 120)));
    }

    @Test
    public void testBlockOutsideWindowIsNotAPortalBlock() {
        // Starting from a non-portal block finds nothing.
        assertNull(find(119, 65, 120, window(120, 65, 120, 121, 67, 120)));
    }
}
