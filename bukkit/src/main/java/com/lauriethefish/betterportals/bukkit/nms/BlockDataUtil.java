package com.lauriethefish.betterportals.bukkit.nms;

import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.nbt.NbtCompound;
import com.lauriethefish.betterportals.api.IntVector;
import com.lauriethefish.betterportals.shared.util.ReflectionUtil;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Helpers for working with tile entities that need to be sent to the client specially.
 */
public class BlockDataUtil {
    private static final Method GET_HANDLE;
    private static final Method GET_BLOCK_ENTITY;
    private static final Method GET_UPDATE_PACKET;
    private static final Constructor<?> BLOCK_POS_CTOR;

    static {
        Class<?> nmsBlockPos = ReflectionUtil.findClass("net.minecraft.core.BlockPos");
        Class<?> nmsLevel = ReflectionUtil.findClass("net.minecraft.world.level.Level");
        Class<?> nmsBlockEntity = ReflectionUtil.findClass("net.minecraft.world.level.block.entity.BlockEntity");

        GET_HANDLE = ReflectionUtil.findMethod(CraftBukkitClassUtil.findCraftBukkitClass("CraftWorld"), "getHandle");
        GET_BLOCK_ENTITY = ReflectionUtil.findMethod(nmsLevel, "getBlockEntity", nmsBlockPos);
        GET_UPDATE_PACKET = ReflectionUtil.findMethod(nmsBlockEntity, "getUpdatePacket");
        BLOCK_POS_CTOR = ReflectionUtil.findConstructor(nmsBlockPos, int.class, int.class, int.class);
    }

    /**
     * Finds the ProtocolLib wrapper around the <code>ClientboundBlockEntityDataPacket</code> which updates the tile entity data for <code>block</code>.
     * @param block The block to get the update packet of
     * @return The ProtocolLib wrapper, or <code>null</code> if the tile entity doesn't send an update packet
     */
    public static @Nullable PacketContainer getUpdatePacket(@NotNull Block block) {
        Object nmsLevel = getNmsLevel(block.getWorld());
        Object nmsBlockPos = ReflectionUtil.invokeConstructor(BLOCK_POS_CTOR, block.getX(), block.getY(), block.getZ());

        Object nmsBlockEntity = ReflectionUtil.invokeMethod(nmsLevel, GET_BLOCK_ENTITY, nmsBlockPos);
        if (nmsBlockEntity == null) {
            return null;
        }

        Object unwrappedPacket = ReflectionUtil.invokeMethod(nmsBlockEntity, GET_UPDATE_PACKET);
        if (unwrappedPacket == null) {
            return null;
        }

        return PacketContainer.fromPacket(unwrappedPacket);
    }

    /**
     * Sets the position of a <code>ClientboundBlockEntityDataPacket</code> in both the NBT and packet itself
     * @param packet The packet to modify the position of
     * @param position The new position
     */
    public static void setTileEntityPosition(@NotNull PacketContainer packet, @NotNull IntVector position) {
        BlockPosition blockPosition = new BlockPosition(position.getX(), position.getY(), position.getZ());
        packet.getBlockPositionModifier().write(0, blockPosition);

        // The NBT Data also stores the position
        NbtCompound compound = (NbtCompound) packet.getNbtModifier().read(0);
        if (Objects.nonNull(compound)) {
            compound.put("x", blockPosition.getX());
            compound.put("y", blockPosition.getY());
            compound.put("z", blockPosition.getZ());
        }
    }

    private static Object getNmsLevel(@NotNull World world) {
        return ReflectionUtil.invokeMethod(world, GET_HANDLE);
    }
}
