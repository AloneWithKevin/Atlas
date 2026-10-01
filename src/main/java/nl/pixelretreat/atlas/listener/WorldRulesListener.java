package nl.pixelretreat.atlas.listener;

import nl.pixelretreat.atlas.api.AtlasFlag;
import nl.pixelretreat.atlas.api.AtlasRules;
import nl.pixelretreat.atlas.service.RulesService;
import nl.pixelretreat.atlas.world.RulesSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.world.ClockTimeSkipEvent;
import org.bukkit.event.world.TimeSkipEvent;
import org.bukkit.projectiles.ProjectileSource;

/**
 * Enforces the world flags. Every handler reads the immutable rules snapshot. Until Atlas has
 * loaded its rules, gameplay changes guarded by a flag are refused rather than allowed.
 */
public final class WorldRulesListener implements Listener {
    private final RulesService rules;

    public WorldRulesListener(RulesService rules) { this.rules = rules; }

    /** Whether the flag allows the action; false before the rules are loaded. */
    private boolean allowed(World world, AtlasFlag flag) {
        RulesSnapshot snapshot = rules.snapshot();
        return snapshot != null && snapshot.flag(RulesService.key(world), flag);
    }

    private void deny(Cancellable event, World world, AtlasFlag flag) {
        if (!allowed(world, flag)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof Player) return;
        AtlasRules.SpawnOrigin origin = event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM
                ? AtlasRules.SpawnOrigin.PLUGIN : AtlasRules.SpawnOrigin.GAME;
        if (!rules.allowsSpawn(entity.getWorld(), entity.getType(), origin)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplosionPrime(ExplosionPrimeEvent event) {
        if (!explosionDisabled(event.getEntity(), event.getEntity().getWorld())) return;
        event.setFire(false);
        event.setRadius(0.0F);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!explosionDisabled(event.getEntity(), event.getLocation().getWorld())) return;
        event.blockList().clear();
        event.setYield(0.0F);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (allowed(event.getBlock().getWorld(), AtlasFlag.EXPLOSION_DAMAGE)) return;
        event.blockList().clear();
        event.setYield(0.0F);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        World world = event.getEntity().getWorld();
        switch (event.getCause()) {
            case CAMPFIRE, FIRE, FIRE_TICK, HOT_FLOOR, LAVA -> deny(event, world, AtlasFlag.FIRE_DAMAGE);
            case FALL -> deny(event, world, AtlasFlag.FALL_DAMAGE);
            case DROWNING -> deny(event, world, AtlasFlag.DROWNING);
            case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> {
                Entity direct = event.getDamageSource().getDirectEntity();
                Entity causing = event.getDamageSource().getCausingEntity();
                if (!allowed(world, AtlasFlag.EXPLOSION_DAMAGE)
                        || (isTnt(direct) || isTnt(causing)) && !allowed(world, AtlasFlag.TNT_DAMAGE)
                        || (isCrystal(direct) || isCrystal(causing)) && !allowed(world, AtlasFlag.CRYSTAL_DAMAGE)) {
                    event.setCancelled(true);
                }
            }
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player && attackingPlayer(event.getDamager()) != null) {
            deny(event, event.getEntity().getWorld(), AtlasFlag.PVP);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && event.getFoodLevel() < player.getFoodLevel()) {
            deny(event, player.getWorld(), AtlasFlag.HUNGER);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityCombust(EntityCombustEvent event) { deny(event, event.getEntity().getWorld(), AtlasFlag.FIRE_DAMAGE); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) { deny(event, event.getBlock().getWorld(), AtlasFlag.FIRE_DAMAGE); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) { deny(event, event.getBlock().getWorld(), AtlasFlag.FIRE_DAMAGE); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        if (isFire(event.getSource().getType()) || isFire(event.getNewState().getType())) {
            deny(event, event.getBlock().getWorld(), AtlasFlag.FIRE_DAMAGE);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) { deny(event, event.getBlock().getWorld(), AtlasFlag.BLOCK_BREAK); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) { deny(event, event.getBlock().getWorld(), AtlasFlag.BLOCK_PLACE); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) { deny(event, event.getBlock().getWorld(), AtlasFlag.LEAF_DECAY); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (event.getBlock().getType() == Material.FARMLAND) deny(event, event.getBlock().getWorld(), AtlasFlag.CROP_TRAMPLING);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) { deny(event, event.getPlayer().getWorld(), AtlasFlag.ITEM_DROP); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) deny(event, player.getWorld(), AtlasFlag.ITEM_PICKUP);
    }

    /** Natural weather changes are refused while the weather cycle is off; Atlas' own changes pass. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWeatherChange(WeatherChangeEvent event) {
        if (event.getCause() != WeatherChangeEvent.Cause.PLUGIN && rules.ready()) deny(event, event.getWorld(), AtlasFlag.WEATHER);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onThunderChange(ThunderChangeEvent event) {
        if (event.getCause() != ThunderChangeEvent.Cause.PLUGIN && rules.ready()) deny(event, event.getWorld(), AtlasFlag.WEATHER);
    }

    /** Sleeping through the night is refused when time skipping is off or the time is fixed. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTimeSkip(TimeSkipEvent event) {
        if (event.getSkipReason() != ClockTimeSkipEvent.SkipReason.NIGHT_SKIP || !rules.ready()) return;
        RulesSnapshot snapshot = rules.snapshot();
        String key = RulesService.key(event.getWorld());
        if (!snapshot.flag(key, AtlasFlag.TIME_SKIP) || snapshot.worldOrBuiltIn(key).fixedTime().isPresent()) {
            event.setCancelled(true);
        }
    }

    private boolean explosionDisabled(Entity entity, World world) {
        return !allowed(world, AtlasFlag.EXPLOSION_DAMAGE)
                || isTnt(entity) && !allowed(world, AtlasFlag.TNT_DAMAGE)
                || isCrystal(entity) && !allowed(world, AtlasFlag.CRYSTAL_DAMAGE);
    }

    private static boolean isTnt(Entity entity) {
        return entity != null && (entity.getType() == EntityType.TNT || entity.getType() == EntityType.TNT_MINECART);
    }

    private static boolean isCrystal(Entity entity) {
        return entity != null && entity.getType() == EntityType.END_CRYSTAL;
    }

    private static Player attackingPlayer(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return player;
        }
        return null;
    }

    private static boolean isFire(Material material) { return material == Material.FIRE || material == Material.SOUL_FIRE; }
}
