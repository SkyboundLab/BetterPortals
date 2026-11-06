package com.lauriethefish.betterportals.bukkit.nms;

import com.lauriethefish.betterportals.shared.util.ReflectionException;
import com.lauriethefish.betterportals.shared.util.ReflectionUtil;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.List;

/**
 * Version specific NMS packet construction.
 * This class only exists because the packet format for some packets (e.g. entity teleports) changed significantly in 26.x,
 * so building them with ProtocolLib's generic field accessors is no longer possible.
 * All methods return the raw NMS packet, to send one wrap it in a {@link com.comphenix.protocol.events.PacketContainer} with {@code PacketContainer.fromPacket}.
 *
 * <p>Since 26.1 the server jar ships with Mojang-mapped names directly
 * (no obfuscation, no remapper), so the classes below are referenced by
 * their real Mojang names ({@code net.minecraft.network.protocol.game.*},
 * {@code net.minecraft.world.phys.Vec3}, …) — there is no obfuscated-name
 * fallback to maintain. See {@link CraftBukkitClassUtil} for the same
 * treatment of CraftBukkit classes.
 *
 * <p>ProtocolLib is deliberately kept for <i>sending</i> these packets.
 * Paper's native packet package ({@code io.papermc.paper.event.packet})
 * was checked against the 26.3 API and contains only four events
 * (chunk load/unload, client tick end, unchecked sign change) — there is
 * no general packet send/receive event in Paper, so migrating packet
 * <i>listening</i> to Paper is not possible; this plugin does not listen
 * to inbound packets at all, it only sends.
 */
public final class PacketFactory {
    // Vec3
    private static final Constructor<?> VEC3_CTOR;
    // PositionMoveRotation(Vec3, Vec3, float, float)
    private static final Constructor<?> POSITION_MOVE_ROTATION_CTOR;
    // ClientboundTeleportEntityPacket(int, PositionMoveRotation, Set<Relative>, boolean)
    private static final Constructor<?> TELEPORT_CTOR;
    // VecDelta$Linear(short, short, short)
    private static final Constructor<?> VEC_DELTA_LINEAR_CTOR;
    // ClientboundMoveEntityPacket$Pos(int, VecDelta, boolean)
    private static final Constructor<?> MOVE_POS_CTOR;
    // ClientboundMoveEntityPacket$Rot(int, byte, byte, boolean)
    private static final Constructor<?> MOVE_ROT_CTOR;
    // ClientboundMoveEntityPacket$PosRot(int, VecDelta, byte, byte, boolean)
    private static final Constructor<?> MOVE_POS_ROT_CTOR;
    // ClientboundRemoveEntitiesPacket(int...)
    private static final Constructor<?> REMOVE_ENTITIES_CTOR;
    // ClientboundSetEntityMotionPacket(int, Vec3)
    private static final Constructor<?> SET_ENTITY_MOTION_CTOR;
    // ClientboundSetEntityDataPacket(int, List<DataValue<?>>)
    private static final Constructor<?> SET_ENTITY_DATA_CTOR;

    static {
        Class<?> vec3 = ReflectionUtil.findClass("net.minecraft.world.phys.Vec3");
        Class<?> positionMoveRotation = ReflectionUtil.findClass("net.minecraft.world.entity.PositionMoveRotation");
        Class<?> vecDelta = ReflectionUtil.findClass("net.minecraft.network.protocol.game.VecDelta");
        Class<?> vecDeltaLinear = ReflectionUtil.findClass("net.minecraft.network.protocol.game.VecDelta$Linear");

        VEC3_CTOR = ReflectionUtil.findConstructor(vec3, double.class, double.class, double.class);
        POSITION_MOVE_ROTATION_CTOR = ReflectionUtil.findConstructor(positionMoveRotation, vec3, vec3, float.class, float.class);
        TELEPORT_CTOR = ReflectionUtil.findConstructor(
                ReflectionUtil.findClass("net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket"),
                int.class, positionMoveRotation, java.util.Set.class, boolean.class);
        VEC_DELTA_LINEAR_CTOR = ReflectionUtil.findConstructor(vecDeltaLinear, short.class, short.class, short.class);
        MOVE_POS_CTOR = ReflectionUtil.findConstructor(
                ReflectionUtil.findClass("net.minecraft.network.protocol.game.ClientboundMoveEntityPacket$Pos"),
                int.class, vecDelta, boolean.class);
        MOVE_ROT_CTOR = ReflectionUtil.findConstructor(
                ReflectionUtil.findClass("net.minecraft.network.protocol.game.ClientboundMoveEntityPacket$Rot"),
                int.class, byte.class, byte.class, boolean.class);
        MOVE_POS_ROT_CTOR = ReflectionUtil.findConstructor(
                ReflectionUtil.findClass("net.minecraft.network.protocol.game.ClientboundMoveEntityPacket$PosRot"),
                int.class, vecDelta, byte.class, byte.class, boolean.class);
        REMOVE_ENTITIES_CTOR = ReflectionUtil.findConstructor(
                ReflectionUtil.findClass("net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket"),
                int[].class);
        SET_ENTITY_MOTION_CTOR = ReflectionUtil.findConstructor(
                ReflectionUtil.findClass("net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket"),
                int.class, vec3);
        SET_ENTITY_DATA_CTOR = ReflectionUtil.findConstructor(
                ReflectionUtil.findClass("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket"),
                int.class, List.class);
    }

    private PacketFactory() {
    }

    /**
     * Creates a {@code ClientboundTeleportEntityPacket} for an entity
     * @param entityId The ID to give the fake entity
     * @param position The absolute position to teleport to
     * @param yaw The rotation around the y axis, in degrees
     * @param pitch The rotation around the x axis, in degrees
     * @param onGround Whether the entity is on the ground
     */
    public static Object teleportEntity(int entityId, @NotNull Location position, float yaw, float pitch, boolean onGround) {
        // All positions and rotations are absolute (no relative flags)
        Object pos = createVec3(position.getX(), position.getY(), position.getZ());
        Object delta = createVec3(0.0, 0.0, 0.0);
        Object change = createPositionMoveRotation(pos, delta, yaw, pitch);

        return invokeConstructorChecked(TELEPORT_CTOR, entityId, change, Collections.<Object>emptySet(), onGround);
    }

    /**
     * Creates a {@code ClientboundMoveEntityPacket$Pos} packet, which moves an entity relative to its current position
     * @param entityId The ID of the entity to move
     * @param offset The relative offset, in blocks
     * @param onGround Whether the entity is on the ground
     */
    public static Object moveEntity(int entityId, @NotNull Vector offset, boolean onGround) {
        Object delta = createVecDelta(offset);

        return invokeConstructorChecked(MOVE_POS_CTOR, entityId, delta, onGround);
    }

    /**
     * Creates a {@code ClientboundMoveEntityPacket$Rot} packet, which rotates an entity
     * @param entityId The ID of the entity to rotate
     * @param yaw Yaw, multiplied by 256/360 (as per the packet format)
     * @param pitch Pitch, multiplied by 256/360 (as per the packet format)
     * @param onGround Whether the entity is on the ground
     */
    public static Object lookEntity(int entityId, int yaw, int pitch, boolean onGround) {
        return invokeConstructorChecked(MOVE_ROT_CTOR, entityId, (byte) yaw, (byte) pitch, onGround);
    }

    /**
     * Creates a {@code ClientboundMoveEntityPacket$PosRot} packet, which moves and rotates an entity
     * @param entityId The ID of the entity to move
     * @param offset The relative offset, in blocks
     * @param yaw Yaw, multiplied by 256/360 (as per the packet format)
     * @param pitch Pitch, multiplied by 256/360 (as per the packet format)
     * @param onGround Whether the entity is on the ground
     */
    public static Object moveLookEntity(int entityId, @NotNull Vector offset, int yaw, int pitch, boolean onGround) {
        Object delta = createVecDelta(offset);

        return invokeConstructorChecked(MOVE_POS_ROT_CTOR, entityId, delta, (byte) yaw, (byte) pitch, onGround);
    }

    /**
     * Creates a {@code ClientboundRemoveEntitiesPacket} packet
     * @param entityIds The IDs of the entities to remove
     */
    public static Object removeEntities(int... entityIds) {
        return invokeConstructorChecked(REMOVE_ENTITIES_CTOR, (Object) entityIds);
    }

    /**
     * Creates a {@code ClientboundSetEntityMotionPacket} packet
     * @param entityId The ID of the entity
     * @param velocity The velocity of the entity, in blocks per tick
     */
    public static Object setEntityMotion(int entityId, @NotNull Vector velocity) {
        Object motion = createVec3(velocity.getX(), velocity.getY(), velocity.getZ());

        return invokeConstructorChecked(SET_ENTITY_MOTION_CTOR, entityId, motion);
    }

    /**
     * Creates a {@code ClientboundSetEntityDataPacket} carrying the entity's
     * non-default data values, or {@code null} if the values cannot be read
     * on this server build — the caller should then skip the metadata send
     * rather than send a malformed packet.
     *
     * <p>Built with NMS directly, not ProtocolLib: in 26.3 the packet
     * carries {@code List<SynchedEntityData.DataValue<?>>}, but ProtocolLib
     * 5.5.0-SNAPSHOT's watchable conversion still produces the old
     * {@code DataItem} objects. The server's encoder then throws
     * {@code ClassCastException: DataItem cannot be cast to DataValue}
     * while writing the packet, which disconnects the receiving client
     * with an {@code EncoderException} (seen live on Paper 26.3-244:
     * every {@code showEntity} re-send of a fake entity killed the
     * viewer). The values here come from
     * {@code SynchedEntityData.getNonDefaultValues()} — exactly the set
     * the vanilla server sends when an entity starts being tracked.
     * @param entityId The ID to give the fake entity
     * @param entity The real entity to read the data values from
     */
    public static @Nullable Object setEntityData(int entityId, @NotNull Entity entity) {
        List<Object> values = EntityUtil.getNonDefaultDataValues(entity);
        if (values == null) { return null; }

        return invokeConstructorChecked(SET_ENTITY_DATA_CTOR, entityId, values);
    }

    private static Object createVecDelta(@NotNull Vector offset) {
        // The game encodes deltas as value * 4096, truncated to the short range
        short x = shortFromDouble(offset.getX() * 4096.0);
        short y = shortFromDouble(offset.getY() * 4096.0);
        short z = shortFromDouble(offset.getZ() * 4096.0);

        return invokeConstructorChecked(VEC_DELTA_LINEAR_CTOR, x, y, z);
    }

    private static short shortFromDouble(double value) {
        long encoded = Math.round(value);

        if (encoded > Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }   else if (encoded < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }

        return (short) encoded;
    }

    private static Object createVec3(double x, double y, double z) {
        return invokeConstructorChecked(VEC3_CTOR, x, y, z);
    }

    private static Object createPositionMoveRotation(Object position, Object deltaMovement, float yRot, float xRot) {
        return invokeConstructorChecked(POSITION_MOVE_ROTATION_CTOR, position, deltaMovement, yRot, xRot);
    }

    private static Object invokeConstructorChecked(Constructor<?> ctor, Object... args) {
        try {
            return ReflectionUtil.invokeConstructor(ctor, args);
        }   catch(ReflectionException ex) {
            throw new IllegalStateException("Failed to create packet with " + ctor, ex);
        }
    }
}
