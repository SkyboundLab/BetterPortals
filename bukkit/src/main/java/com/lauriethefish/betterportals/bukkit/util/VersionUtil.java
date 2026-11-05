package com.lauriethefish.betterportals.bukkit.util;

import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Various parts of the plugin need different implementations based on the minecraft version. */
public class VersionUtil {
    // Cache since it's not super cheap to check if we're above a version
    private static final ConcurrentMap<String, Boolean> versionAtLeastCache = new ConcurrentHashMap<>();

    public static String getCurrentVersion() {
        return Bukkit.getServer().getBukkitVersion();
    }

    /**
     * Finds if the version represented by <code>versionStr</code> is greater than or equal to <code>otherStr</code>/
     * E.g. this would return false for "1.8.9" and "1.9.0", but true for "1.13.0" and "1.12.2".
     * Works for both old (1.x) and new (26.x) version numbering, since it only compares the numeric segments.
     * @param versionStr Version to test if greater
     * @param otherStr Other version
     * @return Whether <code>versionStr</code> is greater than or equal.
     */
    public static boolean isVersionGreaterOrEq(String versionStr, String otherStr) {
        int[] version = splitVersionString(versionStr);
        int[] other = splitVersionString(otherStr);

        for (int i = 0; i < other.length; i++) {
            // If this version has no more numbers but the other does, this version is older
            if (i >= version.length) {
                return false;
            }

            if (version[i] != other[i]) {
                return version[i] > other[i];
            }
        }

        // If all numbers in the other version are equal, this version is >= it
        return true;
    }

    private static int[] splitVersionString(String version) {
        List<Integer> numbers = new ArrayList<>();

        for (String part : version.split("[^0-9]+")) {
            if (part.isEmpty()) {
                continue;
            }

            numbers.add(Integer.parseInt(part));
        }

        return numbers.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Finds if the current minecraft server version is greater than <code>version</code>.
     * @param version The version to test
     * @return Whether the current minecraft server version is greater than <code>version</code>.
     */
    public static boolean isMcVersionAtLeast(String version) {
        Boolean existing = versionAtLeastCache.get(version);
        if (existing != null) { return existing; }

        boolean result = isVersionGreaterOrEq(getCurrentVersion(), version);
        versionAtLeastCache.put(version, result);
        return result;
    }
}
