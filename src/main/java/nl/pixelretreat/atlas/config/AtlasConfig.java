package nl.pixelretreat.atlas.config;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import nl.pixelretreat.atlas.api.AtlasFlag;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** Immutable, validated settings read once at startup. A missing or invalid value stops Atlas. */
public record AtlasConfig(
        String region, Map<String, String> proxyNames,
        String databaseHost, int databasePort, String databaseName, String databaseUsername,
        String databasePassword, String databaseSslMode,
        int packFormat, int safeSearchRadius, int defaultCooldownMillis, int travelTicketMinutes,
        int maxFillBlocks, int maxChunksPerRegion, Map<AtlasFlag, Boolean> defaultFlags,
        int workerThreads, int workerQueueSize) {

    public AtlasConfig {
        proxyNames = Map.copyOf(proxyNames);
        defaultFlags = Map.copyOf(new EnumMap<>(defaultFlags));
    }

    /** Reads every required key; no key has a hidden default. */
    public static AtlasConfig read(FileConfiguration source) {
        ConfigurationSection proxies = source.getConfigurationSection("server.proxy-names");
        if (proxies == null) throw new IllegalArgumentException("Missing server.proxy-names");
        Map<String, String> proxyNames = new java.util.HashMap<>();
        for (String region : proxies.getKeys(false)) proxyNames.put(region, required(proxies, region));
        Map<AtlasFlag, Boolean> flags = new EnumMap<>(AtlasFlag.class);
        for (AtlasFlag flag : AtlasFlag.values()) {
            String path = "default-flags." + flag.key();
            if (!source.isBoolean(path)) throw new IllegalArgumentException("Missing " + path);
            flags.put(flag, source.getBoolean(path));
        }
        var value = new AtlasConfig(
                required(source, "server.region"), proxyNames,
                required(source, "database.host"), source.getInt("database.port"),
                required(source, "database.name"), required(source, "database.username"),
                Objects.requireNonNull(source.getString("database.password"), "database.password"),
                required(source, "database.ssl-mode"),
                source.getInt("datapack.pack-format"), source.getInt("teleport.safe-search-radius"),
                source.getInt("portals.default-cooldown-millis"), source.getInt("portals.travel-ticket-minutes"),
                source.getInt("portals.max-fill-blocks"), source.getInt("keep-loaded.max-chunks-per-region"),
                flags, source.getInt("workers.threads"), source.getInt("workers.queue-size"));
        value.validate();
        return value;
    }

    private static String required(ConfigurationSection source, String key) {
        String value = source.getString(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value.trim();
    }

    private void validate() {
        if (!region.matches("EU|NA") || !proxyNames.containsKey("EU") || !proxyNames.containsKey("NA")
                || databasePort < 1 || databasePort > 65535
                || !databaseSslMode.matches("disable|trust|verify-ca|verify-full")
                || packFormat < 1 || safeSearchRadius < 0 || safeSearchRadius > 64
                || defaultCooldownMillis < 0 || defaultCooldownMillis > 600_000
                || travelTicketMinutes < 1 || travelTicketMinutes > 60
                || maxFillBlocks < 1 || maxFillBlocks > 65_536
                || maxChunksPerRegion < 1 || maxChunksPerRegion > 65_536
                || workerThreads < 1 || workerThreads > 16 || workerQueueSize < 1 || workerQueueSize > 4096) {
            throw new IllegalArgumentException("Invalid Atlas configuration");
        }
        for (String name : proxyNames.values()) {
            if (!name.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid proxy name");
        }
    }

    /** Diagnostics never include the database password. */
    @Override public String toString() {
        return "AtlasConfig[region=" + region + ", proxyNames=" + proxyNames + ", databaseHost=" + databaseHost
                + ", databasePort=" + databasePort + ", databaseName=" + databaseName + ", databaseUsername="
                + databaseUsername + ", databasePassword=***, databaseSslMode=" + databaseSslMode + ", packFormat="
                + packFormat + ", safeSearchRadius=" + safeSearchRadius + ", defaultCooldownMillis="
                + defaultCooldownMillis + ", travelTicketMinutes=" + travelTicketMinutes + ", maxFillBlocks="
                + maxFillBlocks + ", maxChunksPerRegion=" + maxChunksPerRegion + ", defaultFlags=" + defaultFlags
                + ", workerThreads=" + workerThreads + ", workerQueueSize=" + workerQueueSize + "]";
    }

    /** The other Postbox region. */
    public String otherRegion() { return region.equals("EU") ? "NA" : "EU"; }
}
