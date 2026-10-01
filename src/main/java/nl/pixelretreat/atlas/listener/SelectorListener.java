package nl.pixelretreat.atlas.listener;

import java.util.Map;
import nl.pixelretreat.atlas.message.AtlasMessages;
import nl.pixelretreat.atlas.service.RulesService;
import nl.pixelretreat.atlas.service.SelectionService;
import nl.pixelretreat.atlas.world.WorldNames;
import nl.pixelretreat.closet.api.ClosetService;
import nl.pixelretreat.closet.api.ItemId;
import nl.pixelretreat.closet.api.ItemIdentity;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** The Atlas selector: left-click a block for corner 1, right-click for corner 2. */
public final class SelectorListener implements Listener {
    public static final ItemId SELECTOR = ItemId.parse("atlas:selector");
    public static final String ADMIN = "atlas.admin";

    private final ClosetService closet;
    private final SelectionService selections;
    private final AtlasMessages messages;

    public SelectorListener(ClosetService closet, SelectionService selections, AtlasMessages messages) {
        this.closet = closet;
        this.selections = selections;
        this.messages = messages;
    }

    private boolean isSelector(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        ItemIdentity identity = closet.inspect(item);
        return identity.status() == ItemIdentity.Status.KNOWN && SELECTOR.equals(identity.id());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !isSelector(event.getItem())) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!player.hasPermission(ADMIN)) { messages.send(player, "common.permission"); return; }
        Block block = event.getClickedBlock();
        if (block == null) return;
        int corner;
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) corner = 1;
        else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) corner = 2;
        else return;
        String world = RulesService.key(block.getWorld());
        selections.set(player.getUniqueId(), corner, new SelectionService.Corner(world, block.getX(), block.getY(), block.getZ()));
        messages.send(player, corner == 1 ? "selector.first" : "selector.second", Map.of(
                "x", Integer.toString(block.getX()), "y", Integer.toString(block.getY()),
                "z", Integer.toString(block.getZ()), "world", WorldNames.shortName(world)));
    }

    /** The selector never breaks blocks, even in creative mode. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent event) {
        if (isSelector(event.getPlayer().getInventory().getItemInMainHand())) event.setCancelled(true);
    }
}
