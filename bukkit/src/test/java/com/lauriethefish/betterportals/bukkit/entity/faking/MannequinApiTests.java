package com.lauriethefish.betterportals.bukkit.entity.faking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MannequinApiTests {
    @Test
    public void testSpigotVariantResolves() {
        MannequinFakePlayerManager.MannequinApi api = MannequinFakePlayerManager.MannequinApi.resolve();

        assertNotNull(api.setPlayerProfile, "setPlayerProfile(PlayerProfile) should exist on the Spigot API");
        assertNull(api.setProfile, "setProfile(ResolvableProfile) should not exist on the Spigot API");
        assertNull(api.resolvableProfile, "ResolvableProfile factory should not resolve on the Spigot API");
        assertNotNull(api.setDescriptionString, "setDescription(String) should exist on the Spigot API");
        assertNull(api.setDescriptionComponent, "setDescription(Component) should not resolve on the Spigot API");
        assertNull(api.componentEmpty, "Component.empty should not resolve on the Spigot API");
        assertNotNull(api.setHideDescription, "setHideDescription(boolean) should exist on the Spigot API");
    }
}
