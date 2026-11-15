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
    private static final Method GET_NON_DEFAULT_VALUES;
    private static final Constructor<?> SERVER_ENTITY_CTOR;
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
        // Paper 26.3 ServerEntity ctor takes a 6th arg (trackedPlayers set); vanilla/older Paper take 5
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
            return null;
        }
    }

    private static Object createServerEntity(Object serverLevel, Object nmsEntity) {
        Object updateInterval = ReflectionUtil.getField(null, UPDATE_INTERVAL_NEVER);
        Object synchronizer = createNoOpSynchronizer();

        if (SERVER_ENTITY_CTOR_TAKES_TRACKED_PLAYERS) {
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
