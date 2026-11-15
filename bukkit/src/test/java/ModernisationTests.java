import com.lauriethefish.betterportals.api.PortalDirection;
import com.lauriethefish.betterportals.bukkit.entity.PortalMomentum;
import com.lauriethefish.betterportals.bukkit.math.Matrix;
import com.lauriethefish.betterportals.bukkit.util.DimensionUtil;
import org.bukkit.NamespacedKey;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ModernisationTests {
    private static final double EPSILON = 1e-6;


    @Test
    public void testTransformVelocityIdentity() {
        Matrix identity = Matrix.makeRotation(PortalDirection.NORTH, PortalDirection.NORTH);
        Vector out = PortalMomentum.transformVelocity(new Vector(1, 2, 3), identity, 1.0);
        assertEquals(1.0, out.getX(), EPSILON);
        assertEquals(2.0, out.getY(), EPSILON);
        assertEquals(3.0, out.getZ(), EPSILON);
    }

    @Test
    public void testTransformVelocityRotatesAndScales() {
        Matrix rotation = Matrix.makeRotation(PortalDirection.NORTH, PortalDirection.EAST);
        Vector out = PortalMomentum.transformVelocity(new Vector(1, 0, 0), rotation, 2.0);
        assertEquals(0.0, out.getX(), EPSILON);
        assertEquals(0.0, out.getY(), EPSILON);
        assertEquals(-2.0, out.getZ(), EPSILON);
    }

    @Test
    public void testMakeScaleScalesVector() {
        Matrix scale = Matrix.makeScale(2.0);
        Vector out = scale.transform(new Vector(1, 2, 3));
        assertEquals(2.0, out.getX(), EPSILON);
        assertEquals(4.0, out.getY(), EPSILON);
        assertEquals(6.0, out.getZ(), EPSILON);
    }

    @Test
    public void testMakeScaleVectorComponents() {
        Matrix scale = Matrix.makeScale(new Vector(1, 2, 3));
        Vector out = scale.transform(new Vector(1, 1, 1));
        assertEquals(1.0, out.getX(), EPSILON);
        assertEquals(2.0, out.getY(), EPSILON);
        assertEquals(3.0, out.getZ(), EPSILON);
    }


    @Test
    public void testDimensionKeys() {
        assertTrue(DimensionUtil.isNetherKey(new NamespacedKey("minecraft", "the_nether")));
        assertTrue(DimensionUtil.isEndKey(new NamespacedKey("minecraft", "the_end")));
        assertTrue(DimensionUtil.isOverworldKey(new NamespacedKey("minecraft", "overworld")));
        assertFalse(DimensionUtil.isNetherKey(new NamespacedKey("minecraft", "overworld")));
        assertFalse(DimensionUtil.isNetherKey(new NamespacedKey("mydatapack", "the_sift")));
        assertFalse(DimensionUtil.isEndKey(new NamespacedKey("mydatapack", "the_sift")));
        assertFalse(DimensionUtil.isOverworldKey(new NamespacedKey("mydatapack", "the_sift")));
    }

    @Test
    public void testDimensionKeyNulls() {
        assertFalse(DimensionUtil.isNetherKey(null));
        assertFalse(DimensionUtil.isEndKey(null));
        assertFalse(DimensionUtil.isOverworldKey(null));
    }
}
