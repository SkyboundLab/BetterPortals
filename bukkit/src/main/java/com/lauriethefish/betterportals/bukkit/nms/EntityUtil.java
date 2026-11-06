package com.lauriethefish.betterportals.bukkit.nms;

import com.comphenix.protocol.events.PacketContainer;
import com.lauriethefish.betterportals.shared.util.ReflectionUtil;
import com.lauriethefish.betterportals.shared.util.ReflectionException;
import org.bukkit.World;
import org.bukkit.entity.EnderDragonPart;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Marker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class EntityUtil {
    private static final Method GET_HANDLE;
    private static final Method GET_SERVER_HANDLE;
    private static final Method GET_ADD_ENTITY_PACKET;
    private static final Field DATA_WATCHER;
    private static final Field UPDATE_INTERVAL_NEVER;
    /**
     * {@code SynchedEntityData.getNonDefaultValues()}, soft-resolved: null
     * when this server build lacks it (see {@link #getNonDefaultDataValues}).
     * Resolved softly on purpose — a hard lookup here would kill this whole
     * class (and therefore all packet-based entity faking) on any build
     * without the method, the same failure mode the Paper 6-arg
     * ServerEntity constructor used to have.
     */
    private static final Method GET_NON_DEFAULT_VALUES;
    private static final Constructor<?> SERVER_ENTITY_CTOR;
    /**
     * True when {@link #SERVER_ENTITY_CTOR} is Paper's 6-argument form,
     * false for the 5-argument vanilla/older-Paper form.
     */
    private static final boolean SERVER_ENTITY_CTOR_TAKES_TRACKED_PLAYERS;
    private static final Class<?> NMS_SYNCHRONIZER;

    static {
        Class<?> NMS_ENTITY = ReflectionUtil.findClass("net.minecraft.world.entity.Entity");
        Class<?> NMS_SYNCHED_DATA = ReflectionUtil.findClass("net.minecraft.network.syncher.SynchedEntityData");
        Class<?> NMS_SERVER_ENTITY = ReflectionUtil.findClass("net.minecraft.server.level.ServerEntity");
        Class<?> NMS_SERVER_LEVEL = ReflectionUtil.findClass("net.minecraft.server.level.ServerLevel");
        Class<?> NMS_UPDATE_INTERVAL = ReflectionUtil.findClass("net.minecraft.world.entity.UpdateInterval");
        NMS_SYNCHRONIZER = ReflectionUtil.findClass("net.minecraft.server.level.ServerEntity$Synchronizer");

        GET_HANDLE = ReflectionUtil.findMethod(CraftBukkitClassUtil.findCraftBukkitClass("entity.CraftEntity"), "getHandle");
        GET_SERVER_HANDLE = ReflectionUtil.findMethod(CraftBukkitClassUtil.findCraftBukkitClass("CraftWorld"), "getHandle");

        DATA_WATCHER = ReflectionUtil.findFieldByType(NMS_ENTITY, NMS_SYNCHED_DATA);
        UPDATE_INTERVAL_NEVER = ReflectionUtil.findField(NMS_UPDATE_INTERVAL, "NEVER");
        GET_ADD_ENTITY_PACKET = ReflectionUtil.findMethod(NMS_ENTITY, "getAddEntityPacket", NMS_SERVER_ENTITY);
        Method nonDefaultValues;
        try {
            nonDefaultValues = ReflectionUtil.findMethod(NMS_SYNCHED_DATA, "getNonDefaultValues");
        }   catch(ReflectionException ex) {
            nonDefaultValues = null;
        }
        GET_NON_DEFAULT_VALUES = nonDefaultValues;
        // Paper 26.3 (verified against build 244) added a sixth constructor
        // argument — the Set<ServerPlayerConnection> of already-tracking
        // players, stored in the `trackedPlayers` field. Vanilla 26.3 and
        // older Paper builds still have the 5-argument form, so try the
        // Paper form first and fall back. Looking up only one shape is what
        // used to kill this whole class with an ExceptionInInitializerError
        // (and therefore all packet-based entity faking, every tick) on
        // Paper 26.3-244.
        Constructor<?> ctor;
        boolean takesTrackedPlayers;
        try {
            ctor = ReflectionUtil.findConstructor(NMS_SERVER_ENTITY, NMS_SERVER_LEVEL, NMS_ENTITY,
                    NMS_UPDATE_INTERVAL, boolean.class, NMS_SYNCHRONIZER, Set.class);
            takesTrackedPlayers = true;
        }   catch(ReflectionException ex) {
            ctor = ReflectionUtil.findConstructor(NMS_SERVER_ENTITY, NMS_SERVER_LEVEL, NMS_ENTITY,
                    NMS_UPDATE_INTERVAL, boolean.class, NMS_SYNCHRONIZER);
            takesTrackedPlayers = false;
        }
        SERVER_ENTITY_CTOR = ctor;
        SERVER_ENTITY_CTOR_TAKES_TRACKED_PLAYERS = takesTrackedPlayers;
    }

    /**
     * Returns the entity's non-default data values — the
     * {@code SynchedEntityData.DataValue} list a 26.3
     * {@code ClientboundSetEntityDataPacket} carries — or {@code null} if
     * they cannot be read on this server build.
     *
     * <p>This is exactly the set the vanilla server sends when an entity
     * starts being tracked. It is read from the entity's real
     * {@code SynchedEntityData} rather than built through ProtocolLib:
     * ProtocolLib 5.5.0-SNAPSHOT's watchable conversion still produces the
     * old {@code DataItem} objects, and the 26.3 packet's encoder rejects
     * them with {@code ClassCastException: DataItem cannot be cast to
     * DataValue} — which disconnects the receiving client. See
     * {@link PacketFactory#setEntityData}.
     * @param entity The entity to read the data values of
     * @return The non-default data values, or {@code null} if unavailable
     */
    public static @Nullable List<Object> getNonDefaultDataValues(@NotNull Entity entity) {
        if (GET_NON_DEFAULT_VALUES == null) { return null; }
        try {
            Object nmsEntity = ReflectionUtil.invokeMethod(entity, GET_HANDLE);
            Object nmsDataWatcher = ReflectionUtil.getField(nmsEntity, DATA_WATCHER);
            @SuppressWarnings("unchecked")
            List<Object> values = (List<Object>) ReflectionUtil.invokeMethod(nmsDataWatcher, GET_NON_DEFAULT_VALUES);
            return values;
        }   catch(ReflectionException ex) {
            return null;
        }
    }

    /**
     * Getting a valid spawn packet that works correctly for a specific {@link Entity} is surprisingly difficult.
     * This method uses some NMS to get the correct spawn packet.
     * @param entity The entity to get the spawn packet of
     * @return A container with the valid packet, or <code>null</code> if the entity can't be spawned with a packet.
     */
    public static @Nullable PacketContainer getRawEntitySpawnPacket(@NotNull Entity entity) {
        if (entity instanceof EnderDragonPart) return null;
        if (entity instanceof Marker) return null;

        try {
            Object nmsEntity = ReflectionUtil.invokeMethod(entity, GET_HANDLE);
            Object serverLevel = getServerLevel(entity.getWorld());
            Object serverEntity = createServerEntity(serverLevel, nmsEntity);

            Object nmsPacket = ReflectionUtil.invokeMethod(nmsEntity, GET_ADD_ENTITY_PACKET, serverEntity);
            if (nmsPacket == null) {
                return null;
            }

            return PacketContainer.fromPacket(nmsPacket);
        }   catch(ReflectionException | IllegalStateException ex) {
            // Some entities (e.g. markers) can't be spawned with a packet
            return null;
        }
    }

    /**
     * Creates a {@code ServerEntity} with a no-op synchronizer.
     * This is needed since 1.19+ spawn packets are generated by the entity using a server entity as context.
     * The synchronizer is never used, since we don't send anything to the real tracking players.
     */
    private static Object createServerEntity(Object serverLevel, Object nmsEntity) {
        Object updateInterval = ReflectionUtil.getField(null, UPDATE_INTERVAL_NEVER);
        Object synchronizer = createNoOpSynchronizer();

        if (SERVER_ENTITY_CTOR_TAKES_TRACKED_PLAYERS) {
            // Paper stores this set as the entity's `trackedPlayers`. This
            // ServerEntity is a throwaway used only to build a spawn packet,
            // so no players track it yet — an empty (mutable, in case the
            // server adds pairings later) set is the correct value.
            return ReflectionUtil.invokeConstructor(SERVER_ENTITY_CTOR, serverLevel, nmsEntity,
                    updateInterval, false, synchronizer, new HashSet<>());
        }
        return ReflectionUtil.invokeConstructor(SERVER_ENTITY_CTOR, serverLevel, nmsEntity, updateInterval, false, synchronizer);
    }

    private static Object createNoOpSynchronizer() {
        return Proxy.newProxyInstance(NMS_SYNCHRONIZER.getClassLoader(), new Class[]{NMS_SYNCHRONIZER},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> 0;
                    case "toString" -> "BetterPortalsNoOpSynchronizer";
                    case "equals" -> proxy == (args == null ? null : args[0]);
                    default -> null;
                });
    }

    private static Object getServerLevel(@NotNull World world) {
        return ReflectionUtil.invokeMethod(world, GET_SERVER_HANDLE);
    }
}
