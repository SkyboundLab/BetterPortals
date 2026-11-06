package com.lauriethefish.betterportals.bukkit.config;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.lauriethefish.betterportals.shared.logging.Logger;
import lombok.Getter;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Objects;
import java.util.logging.Level;

@Singleton
public class MiscConfig {
    private final Logger logger;

    @Getter private double portalActivationDistance;

    @Getter private boolean entitySupportEnabled;
    @Getter private int entityCheckInterval;

    @Getter private int teleportCooldown;
    @Getter private boolean updateCheckEnabled;

    @Getter private boolean testingCommandsEnabled;

    @Getter private int portalSaveInterval;

    /** If true, players seen through portals are rendered as real Mannequin entities instead of PLAYER_INFO + spawn packets. */
    @Getter private boolean useMannequinFakePlayers;

    /** If true, teleporting through a portal preserves momentum using the 26.2 physics attributes (see PortalMomentum). */
    @Getter private boolean preserveMomentum;

    /** How many ticks the drag-free/bouncy momentum window lasts after a teleport. */
    @Getter private int momentumDragFreeTicks;

    @Inject
    public MiscConfig(Logger logger) {
        this.logger = logger;
    }

    public void load(FileConfiguration config) {
        portalActivationDistance = config.getDouble("portalActivationDistance");
        entitySupportEnabled = config.getBoolean("enableEntitySupport");

        boolean disableEntityCheckInterval = config.getBoolean("checkForEntitiesEveryTick");
        entityCheckInterval = disableEntityCheckInterval ? 1 : config.getInt("entityCheckInterval");
        updateCheckEnabled = config.getBoolean("enableUpdateCheck");

        Level logLevel;
        try {
            logLevel = Level.parse(Objects.requireNonNull(config.getString("logLevel"), "Logging level missing"));
        }   catch(IllegalArgumentException | NullPointerException ex) {
            logger.warning("Invalid logging level found in the config");
            logger.warning("Defaulting to INFO");
            logLevel = Level.INFO;
        }
        logger.setLevel(logLevel);

        teleportCooldown = config.getInt("teleportCooldown");
        testingCommandsEnabled = config.getBoolean("enableTestingCommands");
        portalSaveInterval = config.getInt("portalSaveInterval");

        // getBoolean/getInt with explicit defaults: older config files that
        // predate these keys must not silently turn the features off (or
        // set the window to 0 ticks).
        useMannequinFakePlayers = config.getBoolean("mannequinFakePlayers", true);
        preserveMomentum = config.getBoolean("preserveMomentum", true);
        momentumDragFreeTicks = config.getInt("momentumDragFreeTicks", 10);
        if (momentumDragFreeTicks <= 0) {
            momentumDragFreeTicks = 10;
        }
    }
}
