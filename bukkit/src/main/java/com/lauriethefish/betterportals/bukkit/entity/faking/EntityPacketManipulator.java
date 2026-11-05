package com.lauriethefish.betterportals.bukkit.entity.faking;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.*;
import com.google.inject.Singleton;
import com.lauriethefish.betterportals.bukkit.math.MathUtil;
import com.lauriethefish.betterportals.bukkit.nms.AnimationType;
import com.lauriethefish.betterportals.bukkit.nms.EntityUtil;
import com.lauriethefish.betterportals.bukkit.nms.PacketFactory;
import com.lauriethefish.betterportals.bukkit.nms.RotationUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;


import java.util.*;


/**
 * Deals with sending all of the packets for entity processing
 * Warning: a fair bit of this class is me complaining about mojang
 */
@Singleton
public class EntityPacketManipulator implements IEntityPacketManipulator {
    @Override
    public void showEntity(EntityInfo tracker, Collection<Player> players) {
        // Generate the packet that NMS would normally use to spawn the entity
        PacketContainer spawnPacket = EntityUtil.getRawEntitySpawnPacket(tracker.getEntity());
        if(spawnPacket == null) {return;}

        if(spawnPacket.getUUIDs().size() > 0) {
            spawnPacket.getUUIDs().write(0, tracker.getEntityUniqueId());
        }

        // Use the rendered entity ID
        spawnPacket.getIntegers().write(0, tracker.getEntityId());

        // Translate to the correct rendered position
        Vector actualPos = getPositionFromSpawnPacket(spawnPacket);
        if(tracker.getEntity() instanceof Hanging) {
            actualPos = MathUtil.moveToCenterOfBlock(actualPos);
        }

        Vector renderedPos = tracker.getTranslation().transform(actualPos);
        writePositionToSpawnPacket(spawnPacket, renderedPos);
        setSpawnRotation(spawnPacket, tracker);

        sendPacket(spawnPacket, players);

        // Living Entities also require us to handle entity equipment
        if(tracker.getEntity() instanceof LivingEntity) {
            EntityEquipmentWatcher equipmentWatcher = new EntityEquipmentWatcher((LivingEntity) tracker.getEntity());
            Map<EnumWrappers.ItemSlot, ItemStack> changes = equipmentWatcher.checkForChanges();
            if(changes.size() > 0) {
                sendEntityEquipment(tracker, changes, players);
            }
        }

        sendMetadata(tracker, players);
    }

    // Since 1.19 all entities are spawned with the SPAWN_ENTITY packet, which uses doubles for position
    private Vector getPositionFromSpawnPacket(PacketContainer packet) {
        StructureModifier<Double> doubles = packet.getDoubles();
        return new Vector(
            doubles.read(0),
            doubles.read(1),
            doubles.read(2)
        );
    }

    private void writePositionToSpawnPacket(PacketContainer packet, Vector position) {
        StructureModifier<Double> doubles = packet.getDoubles();
        doubles.write(0, position.getX());
        doubles.write(1, position.getY());
        doubles.write(2, position.getZ());
    }

    // Every packet handles spawn rotation differently for whatever reason
    // This method will set the correct field(s) for whatever packet type
    private void setSpawnRotation(PacketContainer packet, EntityInfo entityInfo) {
        // Calculate the correct byte yaw, pitch, and head rotation for the entity
        Location renderedPos = entityInfo.findRenderedLocation();

        int yaw = RotationUtil.getPacketRotationInt(renderedPos.getYaw());
        int pitch = RotationUtil.getPacketRotationInt(renderedPos.getPitch());

        // Hanging entities (e.g. item frames and paintings) use block faces for their rotation
        if(entityInfo.getEntity() instanceof Hanging) {
            // The spawn packet stores the 3D direction ID as its last integer field (after the entity ID)
            StructureModifier<Integer> integers = packet.getIntegers();
            int dataIndex = integers.size() - 1;
            EnumWrappers.Direction currentDirection = RotationUtil.getDirection(integers.read(dataIndex));

            if(currentDirection != null) {
                EnumWrappers.Direction rotated = RotationUtil.rotateBy(currentDirection, entityInfo.getRotation());
                // Make sure to catch this - it should never happen unless something's gone very wrong
                if (rotated == null) {
                    throw new IllegalStateException("Portal attempted to rotate a hanging entity to an invalid block direction");
                }
                integers.write(dataIndex, RotationUtil.getId(rotated));
            }
        }

        // Set the modified pitch and yaw (xRot, yRot)
        packet.getBytes().write(0, (byte) pitch);
        packet.getBytes().write(1, (byte) yaw);
    }

    @Override
    public void hideEntity(EntityInfo tracker, Collection<Player> players) {
        // The remove entities packet format changed in 26.x, so we construct it directly
        sendNmsPacket(PacketFactory.removeEntities(tracker.getEntityId()), players);
    }

    @Override
    public void sendEntityMove(EntityInfo tracker, Vector offset, Collection<Player> players) {
        offset = tracker.getRotation().transform(offset);

        // We need to convert to the *4096 encoding, like the game does
        sendNmsPacket(PacketFactory.moveEntity(tracker.getEntityId(), offset, tracker.getEntity().isOnGround()), players);
    }

    @Override
    public void sendEntityMoveLook(EntityInfo tracker, Vector offset, Collection<Player> players) {
        Location entityPos = tracker.findRenderedLocation();
        offset = tracker.getRotation().transform(offset); // Make sure to transform the given offset so that it's correct for the rendered position

        int yaw = RotationUtil.getPacketRotationInt(entityPos.getYaw());
        int pitch = RotationUtil.getPacketRotationInt(entityPos.getPitch());

        sendNmsPacket(PacketFactory.moveLookEntity(tracker.getEntityId(), offset, yaw, pitch, tracker.getEntity().isOnGround()), players);
    }


    @Override
    public void sendEntityLook(EntityInfo tracker, Collection<Player> players) {
        // Transform the entity rotation to the origin of the portal
        Location entityPos = tracker.findRenderedLocation();

        int yaw = RotationUtil.getPacketRotationInt(entityPos.getYaw());
        int pitch = RotationUtil.getPacketRotationInt(entityPos.getPitch());

        sendNmsPacket(PacketFactory.lookEntity(tracker.getEntityId(), yaw, pitch, tracker.getEntity().isOnGround()), players);
    }

    @Override
    public void sendEntityTeleport(EntityInfo tracker, Collection<Player> players) {
        Location entityPos = tracker.findRenderedLocation();

        sendNmsPacket(PacketFactory.teleportEntity(tracker.getEntityId(), entityPos, entityPos.getYaw(), entityPos.getPitch(), tracker.getEntity().isOnGround()), players);
    }

    @Override
    public void sendEntityHeadRotation(EntityInfo tracker, Collection<Player> players) {
        // Entity yaw is actually head rotation in Bukkit
        Location renderedPos = tracker.findRenderedLocation();

        // Why.. why is this a byte
        byte headRotation = (byte) (int) (renderedPos.getYaw() * 256.0f / 360.0f);

        PacketContainer packet = new PacketContainer(PacketType.Play.Server.ENTITY_HEAD_ROTATION);
        packet.getIntegers().write(0, tracker.getEntityId());
        packet.getBytes().write(0, headRotation);
        sendPacket(packet, players);
    }

    @Override
    public void sendMount(EntityInfo tracker, Collection<EntityInfo> riding, Collection<Player> players) {
        PacketContainer packet = new PacketContainer(PacketType.Play.Server.MOUNT);

        int[] ridingIds = new int[riding.size()];
        int i = 0;
        for(EntityInfo ridingTracker : riding) {
            ridingIds[i] = ridingTracker.getEntityId();
            i++;
        }

        packet.getIntegers().write(0, tracker.getEntityId());
        packet.getIntegerArrays().write(0, ridingIds);

        sendPacket(packet, players);
    }

    @Override
    public void sendEntityEquipment(EntityInfo tracker, Map<EnumWrappers.ItemSlot, ItemStack> changes, Collection<Player> players) {
        // Why minecraft, why not just use a map...
        List<Pair<EnumWrappers.ItemSlot, ItemStack>> wrappedChanges = new ArrayList<>();
        changes.forEach((slot, item) -> wrappedChanges.add(new Pair<>(slot, item == null ? new ItemStack(Material.AIR) : item)));

        PacketContainer packet = new PacketContainer(PacketType.Play.Server.ENTITY_EQUIPMENT);
        packet.getIntegers().write(0, tracker.getEntityId());
        packet.getSlotStackPairLists().write(0, wrappedChanges);
        sendPacket(packet, players);
    }

    @Override
    public void sendMetadata(EntityInfo tracker, Collection<Player> players) {
        PacketContainer packet = new PacketContainer(PacketType.Play.Server.ENTITY_METADATA);

        packet.getIntegers().write(0, tracker.getEntityId());

        WrappedDataWatcher dataWatcher = EntityUtil.getActualDataWatcher(tracker.getEntity()); // Use the Entity's actual data watcher, not ProtocolLib's method which gives us a dummy
        packet.getWatchableCollectionModifier().write(0, dataWatcher.getWatchableObjects());

        sendPacket(packet, players);
    }

    @Override
    public void sendEntityVelocity(EntityInfo tracker, Vector newVelocity, Collection<Player> players) {
        // Rotate the velocity back to the origin of the portal
        Vector entityVelocity = tracker.getRotation().transform(newVelocity);

        sendNmsPacket(PacketFactory.setEntityMotion(tracker.getEntityId(), entityVelocity), players);
    }

    @Override
    public void sendEntityAnimation(EntityInfo tracker, Collection<Player> players, AnimationType animationType) {
        PacketContainer packet = new PacketContainer(PacketType.Play.Server.ANIMATION);

        StructureModifier<Integer> integers = packet.getIntegers();
        integers.write(0, tracker.getEntityId());
        integers.write(1, animationType.getNmsId());

        sendPacket(packet, players);
    }

    @Override
    public void sendEntityPickupItem(EntityInfo tracker, EntityInfo pickedUp, Collection<Player> players) {
        PacketContainer packet = new PacketContainer(PacketType.Play.Server.COLLECT);

        StructureModifier<Integer> integers = packet.getIntegers();
        integers.write(0, pickedUp.getEntityId());
        integers.write(1, tracker.getEntityId());
        integers.write(2, ((Item) pickedUp.getEntity()).getItemStack().getAmount());

        sendPacket(packet, players);
    }

    private PlayerInfoData generatePlayerInfoData(EntityInfo tracker) {
        // We make a new profile for our fake user, with our fake UUID and the original name
        WrappedGameProfile profile = new WrappedGameProfile(tracker.getEntityUniqueId(), tracker.getEntity().getName());

        Player trackingPlayer = (Player) tracker.getEntity();
        WrappedGameProfile playerProfile = WrappedGameProfile.fromPlayer(trackingPlayer);

        // We remove the existing textures (if any) and add the skin of the original player
        profile.getProperties().removeAll("textures");
        profile.getProperties().putAll("textures", playerProfile.getProperties().get("textures"));


        return new PlayerInfoData(
                profile,
                trackingPlayer.getPing(),
                EnumWrappers.NativeGameMode.fromBukkit(trackingPlayer.getGameMode()),
                WrappedChatComponent.fromText(trackingPlayer.getName())
        );
    }

    @Override
    public void sendAddPlayerProfile(EntityInfo tracker, Collection<Player> players) {
        // The player info packet is now split into update and remove packets, with an action set
        PacketContainer packet = new PacketContainer(PacketType.Play.Server.PLAYER_INFO);
        packet.getPlayerInfoActions().write(0, EnumSet.of(EnumWrappers.PlayerInfoAction.ADD_PLAYER));

        List<PlayerInfoData> playerInfoDataList = new ArrayList<>();
        playerInfoDataList.add(generatePlayerInfoData(tracker));
        packet.getPlayerInfoDataLists().write(0, playerInfoDataList);
        sendPacket(packet, players);
    }

    @Override
    public void sendRemovePlayerProfile(EntityInfo tracker, Collection<Player> players) {
        PacketContainer packet = new PacketContainer(PacketType.Play.Server.PLAYER_INFO_REMOVE);
        packet.getUUIDLists().write(0, Collections.singletonList(tracker.getEntityUniqueId()));
        sendPacket(packet, players);
    }

    private void sendPacket(PacketContainer packet, Collection<Player> players) {
        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
        for (Player player : players) {
            protocolManager.sendServerPacket(player, packet, false);
        }
    }

    private void sendNmsPacket(Object nmsPacket, Collection<Player> players) {
        sendPacket(PacketContainer.fromPacket(nmsPacket), players);
    }
}
