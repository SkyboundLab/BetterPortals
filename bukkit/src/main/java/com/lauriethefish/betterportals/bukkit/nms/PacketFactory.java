package com.lauriethefish.betterportals.bukkit.nms;

import com.lauriethefish.betterportals.shared.util.ReflectionException;
import com.lauriethefish.betterportals.shared.util.ReflectionUtil;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Constructor;
import java.util.Collections;

/**
 * Version specific NMS packet construction.
 * This class only exists because the packet format for some packets (e.g. entity teleports) changed significantly in 26.x,
 * so building them with ProtocolLib's generic field accessors is no longer possible.
 * All methods return the raw NMS packet, to send one wrap it in a {@link com.comphenix.protocol.events.PacketContainer} with {@code PacketContainer.fromPacket}.
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
