package net.democracycraft.elections.internal.util.time;

import com.djrapitops.plan.query.CommonQueries;
import com.djrapitops.plan.query.QueryService;
import net.democracycraft.elections.Elections;
import net.democracycraft.elections.internal.util.config.ConfigPaths;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
     * @return CompletableFuture with playtime in milliseconds.
     */
    public static CompletableFuture<Long> getPlaytimeMillis(Player player, int days) {
        if (player == null) return CompletableFuture.completedFuture(0L);

        return CompletableFuture.supplyAsync(() -> {
            // Use Plan API as the authoritative source
            try {
                QueryService service = QueryService.getInstance();
                CommonQueries queries = service.getCommonQueries();

                if (queries != null) {
                    long now = System.currentTimeMillis();
                    long lookBackMillis = TimeUnit.DAYS.toMillis(days);
                    long start = now - lookBackMillis;

                    UUID playerUniqueIdentifier = player.getUniqueId();

                    String sql = "SELECT SUM((s.session_end - s.session_start) - s.afk_time) " +
                            "FROM plan_sessions s " +
                            "JOIN plan_users u ON s.user_id = u.id " +
                            "WHERE u.uuid = ? AND s.session_start >= ?";

                    long history = service.query(sql, preparedStatement -> {
                                preparedStatement.setString(1, playerUniqueIdentifier.toString());
                                preparedStatement.setLong(2, start);
                                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                                    if (resultSet.next()) {
                                        return resultSet.getLong(1);
                                    }
                                    return 0L;
                                }
                            }
                    );

                    long current = queries.fetchCurrentSessionPlaytime(playerUniqueIdentifier);

                    return history + current;
                }
            } catch (Exception ignored) {
                // Plan is unavailable or query failed; will fallback to Bukkit's
            }

            // Fallback to Bukkit ONLY if Plan is completely unavailable
            return getBukkitPlaytimeMillis(player);
        });
    }

    /**
     * Gets the player's total playtime from Bukkit statistics in milliseconds.
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
     * @return CompletableFuture with playtime in seconds
     */
    public static @NonNull CompletableFuture<Long> getPlaytimeSeconds(Player player) {
        return getPlaytimeSeconds(player, getConfiguredLookbackDays());
    }

    public static @NonNull CompletableFuture<Long> getPlaytimeSeconds(Player player, int days) {
        return getPlaytimeMillis(player, days).thenApply(TimeUnit.MILLISECONDS::toSeconds);
    }

    /**
     * Returns the player's playtime in minutes for the configured lookback period.
     * @param player the player to get playtime for
     * @return CompletableFuture with playtime in minutes
     */
    public static @NonNull CompletableFuture<Long> getPlaytimeMinutes(Player player) {
        return getPlaytimeMinutes(player, getConfiguredLookbackDays());
    }

    public static @NonNull CompletableFuture<Long> getPlaytimeMinutes(@NonNull Player player, int days) {
        return getPlaytimeMillis(player, days).thenApply(TimeUnit.MILLISECONDS::toMinutes);
    }

    /**
     * Returns the player's playtime in hours for the configured lookback period.
     * @param player the player to get playtime for
     * @return CompletableFuture with playtime in hours
     */
    public static @NonNull CompletableFuture<Long> getPlaytimeHours(Player player) {
        return getPlaytimeHours(player, getConfiguredLookbackDays());
    }

    public static @NonNull CompletableFuture<Long> getPlaytimeHours(Player player, int days) {
        return getPlaytimeMillis(player, days).thenApply(TimeUnit.MILLISECONDS::toHours);
    }

    /**
     * Returns the player's playtime in days for the configured lookback period.
     * @param player the player to get playtime for
     * @return CompletableFuture with playtime in days
     */
    public static CompletableFuture<Long> getPlaytimeDays(Player player) {
        return getPlaytimeDays(player, getConfiguredLookbackDays());
    }

    public static CompletableFuture<Long> getPlaytimeDays(Player player, int days) {
        return getPlaytimeMillis(player, days).thenApply(TimeUnit.MILLISECONDS::toDays);
    }
}