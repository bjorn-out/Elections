package net.democracycraft.elections;

import net.democracycraft.democracyLib.api.DemocracyLibApi;
import net.democracycraft.democracyLib.api.service.mojang.MojangService;
import net.democracycraft.elections.api.model.BallotErrorConfigProvider;
import net.democracycraft.elections.api.service.ElectionsService;
import net.democracycraft.elections.internal.command.ElectionsCommand;
import net.democracycraft.elections.internal.database.DatabaseSchema;
import net.democracycraft.elections.internal.database.MySQLManager;
import net.democracycraft.elections.internal.service.SqlElectionsService;
import net.democracycraft.elections.internal.ui.common.ErrorMenu;
import net.democracycraft.elections.internal.ui.common.LoadingMenu;
import net.democracycraft.elections.internal.ui.list.ElectionPreviewMenu;
import net.democracycraft.elections.internal.ui.manager.*;
import net.democracycraft.elections.internal.ui.manager.create.*;
import net.democracycraft.elections.internal.ui.vote.*;
import net.democracycraft.elections.internal.util.export.github.GitHubGistClient;
import net.democracycraft.elections.internal.util.head.PlayerHeadCache;
import net.democracycraft.elections.internal.util.listener.PollInteractListener;
import net.democracycraft.elections.internal.util.permissions.PermissionNodesStore;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.Listener;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import net.democracycraft.elections.internal.util.config.ConfigPaths;
import net.democracycraft.elections.internal.util.export.local.queue.LocalExportedElectionQueue;
import net.democracycraft.elections.internal.util.time.PlayerPlaytimeUtil;
import org.jetbrains.annotations.NotNull;

/**
 * Main plugin entry point for DemocracyElections.
 */
public class Elections extends JavaPlugin {

    private static Elections instance;
    /**
     * @return the active plugin singleton instance.
     */
    public static Elections getInstance() { return instance; }

    private ElectionsService electionsService;
    private PermissionNodesStore permissionNodesStore;
    private MySQLManager mysql;
    private DatabaseSchema schema;
    private BukkitTask autoCloseTask;
    private BukkitTask deletedPurgeTask;
    private LocalExportedElectionQueue localQueue;
    private PlayerHeadCache playerHeadCache;
    private DemocracyLibApi democracyLibApi;
    private MojangService<Elections> mojangService;

    @Override
    public void onEnable() {
        instance = this;
        democracyLibApi = DemocracyLibApi.instance(this,true);
        mojangService = democracyLibApi.getMojangService(this);
        saveDefaultConfig();

        getConfig().options().copyDefaults(true);
        saveConfig();

        BallotErrorConfigProvider.init();

        // MySQL + schema
        this.mysql = new MySQLManager(this);
        this.mysql.setupDatabase();
        // Force a connectivity check and fail fast if unavailable
        try {
            this.mysql.getConnection();
        } catch (Exception ex) {
            getLogger().severe("MySQL is not reachable or credentials are invalid. Plugin will be disabled. Cause: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            this.schema = new DatabaseSchema(mysql);
            this.schema.createAll();
        } catch (Exception ex) {
            getLogger().severe("Failed to initialize database schema. Plugin will be disabled. Cause: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Persistent service backed by SQL with in-memory mirror
        this.electionsService = new SqlElectionsService(this, mysql, schema);
        // Register service for third-party plugins via Bukkit ServicesManager
        getServer().getServicesManager().register(ElectionsService.class, this.electionsService, this, ServicePriority.Highest);

        // Local queue for exports
        this.localQueue = new LocalExportedElectionQueue(this);

        // Schedule periodic auto-close sweep asynchronously (avoid DB on main thread)
        SqlElectionsService sql = (SqlElectionsService) this.electionsService;
        int seconds = getConfig().getInt(ConfigPaths.AUTO_CLOSE_SWEEP_SECONDS.getPath(), 60);
        if (seconds < 1) seconds = 60; // sane fallback
        this.autoCloseTask = getServer().getScheduler().runTaskTimerAsynchronously(this, sql::runAutoCloseSweep, 20L * 30, 20L * seconds);

        // Schedule periodic purge of DELETED elections after retention
        int purgeSeconds = getConfig().getInt(ConfigPaths.DELETED_PURGE_SWEEP_SECONDS.getPath(), 3600);
        int retentionDays = getConfig().getInt(ConfigPaths.DELETED_RETENTION_DAYS.getPath(), 30);
        if (purgeSeconds < 60) purgeSeconds = 3600;
        if (retentionDays < 0) retentionDays = 30;
        final int rd = retentionDays;
        this.deletedPurgeTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> sql.runDeletedPurgeSweep(rd), 20L * 60, 20L * purgeSeconds);

        this.permissionNodesStore = new PermissionNodesStore();
        this.playerHeadCache = new PlayerHeadCache(this);

        startMainCommand();

        registerListener(new PollInteractListener(electionsService, this));

        loadConfig();
    }

    public MojangService<Elections> getMojangService() {
        return mojangService;
    }

    public DemocracyLibApi getDemocracyLibApi() {
        return democracyLibApi;
    }

    private void registerListener(Listener listener){
        getServer().getPluginManager().registerEvents(listener, this);
    }

    private void startMainCommand() {
        PluginCommand cmd = getCommand("elections");
        if (cmd != null) {
            ElectionsCommand executor = new ElectionsCommand(this, electionsService);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        } else {
            getLogger().severe("Command 'elections' not found in plugin.yml");
        }
    }

    @Override
    public void onDisable() {
        if (autoCloseTask != null) {
            autoCloseTask.cancel();
            autoCloseTask = null;
        }
        if (deletedPurgeTask != null) {
            deletedPurgeTask.cancel();
            deletedPurgeTask = null;
        }
        // Unregister provided service
        getServer().getServicesManager().unregisterAll(this);
        // Shutdown async executor in service (if present)
        if (electionsService instanceof SqlElectionsService sql) {
            try { sql.shutdown(); } catch (Exception ignored) {}
        }
        // Disconnect from MySQL
        if (mysql != null) {
            try { mysql.disconnect(); } catch (Exception ignored) {}
        }
    }

    /**
     * @return the elections application service instance.
     */
    public ElectionsService getElectionsService() {
        return electionsService;
    }

    /**
     * @return the YAML-backed permission nodes store used for UI filtering.
     */
    public PermissionNodesStore getPermissionNodesStore() {
        return permissionNodesStore;
    }

    /**
     * @return the MySQL manager instance for database operations.
     */
    public MySQLManager getMySQLManager() { return mysql; }

    /**
     * @return the database schema and AutoTable registry.
     */
    public DatabaseSchema getSchema() { return schema; }

    /**
     * @return local export queue singleton.
     */
    public LocalExportedElectionQueue getLocalExportQueue() { return localQueue; }

    public @NotNull PlayerHeadCache getPlayerHeadCache() {
        if (playerHeadCache == null) {
            playerHeadCache = new PlayerHeadCache(this);
        }
        return playerHeadCache;
    }

    private void loadConfig() {
        PlayerPlaytimeUtil.reloadConfig();
        GitHubGistClient.loadConfig();
        ErrorMenu.Config.loadConfig();
        LoadingMenu.Config.loadConfig();
        ElectionPreviewMenu.Config.loadConfig();
        ElectionCreateBasicsMenu.Config.loadConfig();
        ElectionCreateConfirmMenu.Config.loadConfig();
        ElectionCreateDurationMenu.Config.loadConfig();
        ElectionCreateRequirementsMenu.Config.loadConfig();
        ElectionCreateSystemMenu.Config.loadConfig();
        ElectionCreateWizard.Config.loadConfig();
        BallotModeMenu.Config.loadConfig();
        CandidatesAddMenu.Config.loadConfig();
        CandidateEditMenu.Config.loadConfig();
        DurationMenu.Config.loadConfig();
        ElectionListMenu.Config.loadConfig();
        ElectionManagerMenu.Config.loadConfig();
        PollsConfigMenu.Config.loadConfig();
        RequirementsMenu.Config.loadConfig();
        SystemAndMinimumMenu.Config.loadConfig();
        TitleEditMenu.Config.loadConfig();
        BallotIntroMenu.Config.loadConfig();
        CandidateListMenu.Config.loadConfig();
        CandidateVoteListMenu.Config.loadConfig();
        CandidateVoteMenu.Config.loadConfig();
        PreferentialBallotMenu.Config.loadConfig();
        SimpleBlockBallotMenu.Config.loadConfig();
        SimplePreferentialBallotMenu.Config.loadConfig();
    }
}
