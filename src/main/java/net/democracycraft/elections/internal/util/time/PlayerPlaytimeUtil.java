package net.democracycraft.elections.internal.util.time;

import com.djrapitops.plan.query.CommonQueries;
import com.djrapitops.plan.query.QueryService;
import net.democracycraft.elections.Elections;
import net.democracycraft.elections.internal.util.config.ConfigPaths;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Helper utility to get player's active playtime.
 * It prioritizes the Plan API to fetch playtime within a specific timeframe (e.g., last 30 days).
 * If Plan is unavailable or fails, it falls back to Bukkit's native statistics (Total Playtime).
 */
public final class PlayerPlaytimeUtil {

    private static final int DEFAULT_LOOKBACK_DAYS = 30;

    /** Cached lookback days value, initialized on first use or via reloadConfig() */
    private static int cachedLookbackDays = -1;

    private PlayerPlaytimeUtil() {}

    /**
     * Reloads the lookback days configuration from the plugin config.
     * Should be called when the plugin loads or when config is reloaded.
     */
    public static void reloadConfig() {
        try {
            Elections plugin = Elections.getInstance();
            if (plugin != null) {
                int days = plugin.getConfig().getInt(ConfigPaths.PLAYTIME_LOOKBACK_DAYS.getPath(), DEFAULT_LOOKBACK_DAYS);
                cachedLookbackDays = days > 0 ? days : DEFAULT_LOOKBACK_DAYS;
                return;
            }
        } catch (Exception ignored) {
        }
        cachedLookbackDays = DEFAULT_LOOKBACK_DAYS;
    }

    /**
     * Gets the configured lookback period in days.
     * Uses cached value for performance; call reloadConfig() to refresh.
     *
     * @return The configured lookback period in days.
     */
    private static int getConfiguredLookbackDays() {
        if (cachedLookbackDays < 0) {
            reloadConfig();
        }
        return cachedLookbackDays;
    }

    /**
     * Gets the playtime in milliseconds for the specified lookback period.
     * Uses Plan API as the authoritative source for active playtime.
     * Falls back to Bukkit total playtime ONLY if Plan is completely unavailable.
     *<p></p>
     * Note: Plan may cache data and not update in real-time, but it provides
     * accurate active playtime tracking which is essential for security.
     *
     * @param player The player to query.
     * @param days   The number of days to look back (e.g., 30 for the last month).
     * @return Playtime in milliseconds.
     */
    public static long getPlaytimeMillis(Player player, int days) {
        if (player == null) return 0L;

        // Use Plan API as the authoritative source
        try {
            QueryService service = QueryService.getInstance();
            CommonQueries queries = service.getCommonQueries();

            if (queries != null) {
                long now = System.currentTimeMillis();
                long lookBackMillis = TimeUnit.DAYS.toMillis(days);
                long start = now - lookBackMillis;

                UUID playerUniqueIdentifier = player.getUniqueId();

                UUID serverUUID = service.getServerUUID().orElse(null);

                long history = queries.fetchPlaytime(playerUniqueIdentifier, serverUUID, start, now);

                long current = queries.fetchCurrentSessionPlaytime(playerUniqueIdentifier);

                return history + current;
            }
        } catch (Exception ignored) {
            // Plan not available, fall through to Bukkit fallback
        }

        // Fallback to Bukkit ONLY if Plan is completely unavailable
        // This is less secure as it doesn't track active playtime accurately
        return getBukkitPlaytimeMillis(player);
    }

    /**
     * Gets the player's total playtime from Bukkit statistics in milliseconds.
     * WARNING: This is only used as a fallback when Plan is unavailable.
     * Bukkit stats can be manipulated and don't accurately reflect active playtime.
     */
    private static long getBukkitPlaytimeMillis(Player player) {
        try {
            // PLAY_ONE_MINUTE increments once per tick (20 ticks = 1 second)
            long ticks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
            // Convert ticks to milliseconds: ticks / 20 = seconds, * 1000 = milliseconds
            return (ticks / 20L) * 1000L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Returns the player's playtime in seconds for the configured lookback period.
     * @param player the player to get playtime for
     * @return playtime in seconds
     */
    public static long getPlaytimeSeconds(Player player) {
        return getPlaytimeSeconds(player, getConfiguredLookbackDays());
    }

    public static long getPlaytimeSeconds(Player player, int days) {
        return TimeUnit.MILLISECONDS.toSeconds(getPlaytimeMillis(player, days));
    }

    /**
     * Returns the player's playtime in minutes for the configured lookback period.
     * @param player the player to get playtime for
     * @return playtime in minutes
     */
    public static long getPlaytimeMinutes(Player player) {
        return getPlaytimeMinutes(player, getConfiguredLookbackDays());
    }

    public static long getPlaytimeMinutes(Player player, int days) {
        return TimeUnit.MILLISECONDS.toMinutes(getPlaytimeMillis(player, days));
    }

    /**
     * Returns the player's playtime in hours for the configured lookback period.
     * @param player the player to get playtime for
     * @return playtime in hours
     */
    public static long getPlaytimeHours(Player player) {
        return getPlaytimeHours(player, getConfiguredLookbackDays());
    }

    public static long getPlaytimeHours(Player player, int days) {
        return TimeUnit.MILLISECONDS.toHours(getPlaytimeMillis(player, days));
    }

    /**
     * Returns the player's playtime in days for the configured lookback period.
     * @param player the player to get playtime for
     * @return playtime in days
     */
    public static long getPlaytimeDays(Player player) {
        return getPlaytimeDays(player, getConfiguredLookbackDays());
    }

    public static long getPlaytimeDays(Player player, int days) {
        return TimeUnit.MILLISECONDS.toDays(getPlaytimeMillis(player, days));
    }
}