package com.lauriethefish.betterportals.bukkit.entity.faking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Mannequin API adapter ({@link MannequinFakePlayerManager.MannequinApi}).
 * These run against the compile-time (Spigot) API, so they assert the
 * Spigot variant resolves there. The Paper variant (setProfile /
 * ResolvableProfile / Component description) cannot be exercised from
 * this classpath — it is verified separately against the real Paper
 * 26.3-244 API jar, where the Spigot variant must NOT resolve.
 */
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
