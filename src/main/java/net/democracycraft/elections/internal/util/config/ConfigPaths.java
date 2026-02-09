package net.democracycraft.elections.internal.util.config;

public enum ConfigPaths {
    AUTO_CLOSE_SWEEP_SECONDS("elections.autoCloseSweepSeconds"),
    DELETED_PURGE_SWEEP_SECONDS("elections.deletedPurgeSweepSeconds"),
    DELETED_RETENTION_DAYS("elections.deletedRetentionDays"),
    PLAYTIME_LOOKBACK_DAYS("elections.playtimeLookbackDays"),
    MYSQL_HOST("mysql.host"),
    MYSQL_PORT("mysql.port"),
    MYSQL_DATABASE("mysql.database"),
    MYSQL_USER("mysql.user"),
    MYSQL_PASSWORD("mysql.password"),
    MYSQL_USE_SSL("mysql.useSSL");

    private final String path;

    ConfigPaths(String path) {
        this.path = path;
    }

    public String getPath() {
        return path;
    }
}
