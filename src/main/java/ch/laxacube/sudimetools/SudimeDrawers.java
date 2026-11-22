package ch.laxacube.sudimetools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockBreakEvent;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Direction;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Drawer en Sudime : un bloc de stockage massif avec jusqu'à 4 compartiments.
 * <p>
 * Le bloc et ses états sont définis dans CraftEngine (sudime:sudime_drawer) ;
 * tout le stockage est géré ici, dans plugins/SudimeTools/drawers.yml.
 * <ul>
 *     <li>Clic droit : dépose la pile en main dans le compartiment cliqué.</li>
 *     <li>Clic gauche : retire une pile du compartiment cliqué.</li>
 *     <li>Maj + clic gauche : retire tout le compartiment.</li>
 *     <li>Clic droit avec un Upgrade de Drawer : ajoute un compartiment (max 4).</li>
 * </ul>
 */
public final class SudimeDrawers implements Listener {

    private static final String DRAWER_ID = "sudime:sudime_drawer";
    private static final String UPGRADE_ID = "sudime:drawer_upgrade";
    private static final int MAX_SLOTS = 4;
    /** Nombre de piles de 64 stockables par compartiment. */
    private static final int STACKS_PER_SLOT = 20;
    private static final String HOLO_TAG = "sudime_drawer_holo";

    private final JavaPlugin plugin;
    private final NamespacedKey holoKey;
    private final File file;
    private final Map<String, Drawer> drawers = new HashMap<>();

    public SudimeDrawers(JavaPlugin plugin) {
        this.plugin = plugin;
        this.holoKey = new NamespacedKey(plugin, "drawer_holo");
        this.file = new File(plugin.getDataFolder(), "drawers.yml");
    }

    public void enable() {
        load();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        // Les chunks déjà chargés n'émettent pas d'événement : on rafraîchit après le démarrage.
        plugin.getServer().getScheduler().runTaskLater(plugin, this::refreshLoaded, 60L);
    }

    public void disable() {
        save();
    }

    // ------------------------------------------------------------------
    // Modèle de données
    // ------------------------------------------------------------------

    private static final class Drawer {
        final ItemStack[] items = new ItemStack[MAX_SLOTS];
        final int[] counts = new int[MAX_SLOTS];
        int unlocked = 1;

        boolean isEmpty() {
            for (int count : counts) {
                if (count > 0) {
                    return false;
                }
            }
            return true;
        }
    }

    private static String key(Block block) {
        return block.getWorld().getName() + ";" + block.getX()
                + ";" + block.getY() + ";" + block.getZ();
    }

    private static Location parseKey(String key) {
        String[] parts = key.split(";");
        if (parts.length != 4) {
            return null;
        }
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) {
            return null;
        }
        try {
            return new Location(world, Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private boolean isDrawer(Block block) {
        try {
            ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
            return state != null && DRAWER_ID.equals(state.owner().value().id().asString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Drawer getOrCreate(Block block) {
        return drawers.computeIfAbsent(key(block), ignored -> new Drawer());
    }

    // ------------------------------------------------------------------
    // Sauvegarde
    // ------------------------------------------------------------------

    private void load() {
        drawers.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("drawers");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            Location location = parseKey(key);
            if (location == null) {
                continue;
            }
            Drawer drawer = new Drawer();
            drawer.unlocked = Math.max(1, Math.min(MAX_SLOTS,
                    root.getInt(key + ".unlocked", 1)));
            for (int i = 0; i < MAX_SLOTS; i++) {
                String encoded = root.getString(key + ".slots." + i + ".item");
                int count = root.getInt(key + ".slots." + i + ".count", 0);
                if (encoded == null || count <= 0) {
                    continue;
                }
                try {
                    ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
                    item.setAmount(1);
                    drawer.items[i] = item;
                    drawer.counts[i] = count;
                } catch (Throwable ignored) {
                    // Item corrompu / version incompatible : on ignore ce compartiment.
                }
            }
            drawers.put(key, drawer);
        }
        plugin.getLogger().info("Drawers en Sudime : " + drawers.size() + " drawer(s) chargé(s).");
    }

    private void save() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, Drawer> entry : drawers.entrySet()) {
            Drawer drawer = entry.getValue();
            String base = "drawers." + entry.getKey();
            yaml.set(base + ".unlocked", drawer.unlocked);
            for (int i = 0; i < MAX_SLOTS; i++) {
                if (drawer.items[i] == null || drawer.counts[i] <= 0) {
                    continue;
                }
                String encoded = Base64.getEncoder().encodeToString(drawer.items[i].serializeAsBytes());
                yaml.set(base + ".slots." + i + ".item", encoded);
                yaml.set(base + ".slots." + i + ".count", drawer.counts[i]);
            }
        }
        try {
            yaml.save(file);
        } catch (IOException exception) {
            plugin.getLogger().warning("Impossible de sauvegarder les drawers : " + exception.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Interactions
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        if (!isDrawer(block)) {
            return;
        }
        Player player = event.getPlayer();
        Drawer drawer = getOrCreate(block);
        int slot = slotFor(event, drawer);

        if (action == Action.LEFT_CLICK_BLOCK) {
            // On ne cancel pas : la casse du bloc (appui long) reste possible.
            extract(player, block, drawer, slot);
            return;
        }

        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        insert(player, block, drawer, slot);
    }

    /** Compartiment visé selon le quartier cliqué sur la face. */
    private int slotFor(PlayerInteractEvent event, Drawer drawer) {
        Vector hit = event.getClickedPosition();
        double u;
        double v;
        if (hit == null) {
            u = 0.5;
            v = 0.5;
        } else if (event.getBlockFace() == null) {
            u = hit.getX();
            v = hit.getY();
        } else {
            switch (event.getBlockFace()) {
                case NORTH -> {
                    u = 1.0 - hit.getX();
                    v = hit.getY();
                }
                case EAST -> {
                    u = 1.0 - hit.getZ();
                    v = hit.getY();
                }
                case WEST -> {
                    u = hit.getZ();
                    v = hit.getY();
                }
                case UP -> {
                    u = hit.getX();
                    v = 1.0 - hit.getZ();
                }
                case DOWN -> {
                    u = hit.getX();
                    v = hit.getZ();
                }
                default -> {
                    u = hit.getX();
                    v = hit.getY();
                }
            }
        }
        int quadrant = (u < 0.5 ? 0 : 1) + (v < 0.5 ? 2 : 0);
        return Math.min(quadrant, drawer.unlocked - 1);
    }

    private void insert(Player player, Block block, Drawer drawer, int preferred) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            return;
        }
        if (UPGRADE_ID.equals(SudimeTools.ceId(hand))) {
            upgrade(player, block, drawer);
            return;
        }

        int moved;
        if (player.isSneaking()) {
            moved = depositAll(player, drawer, preferred);
        } else {
            moved = insertInto(drawer, preferred, hand);
            if (moved > 0) {
                int remaining = hand.getAmount() - moved;
                if (remaining <= 0) {
                    player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                } else {
                    hand.setAmount(remaining);
                    player.getInventory().setItemInMainHand(hand);
                }
            }
        }

        if (moved <= 0) {
            player.sendActionBar(Component.text("Drawer plein", NamedTextColor.RED));
            player.playSound(block.getLocation(), Sound.BLOCK_DECORATED_POT_INSERT_FAIL, 1f, 0.8f);
            return;
        }
        save();
        refresh(block);
        player.playSound(block.getLocation(), Sound.BLOCK_DECORATED_POT_INSERT, 1f, 1.2f);
    }

    /** Dépose tous les items correspondants de l'inventaire (Maj + clic droit). */
    private int depositAll(Player player, Drawer drawer, int preferred) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        int total = 0;
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType().isAir()
                    || UPGRADE_ID.equals(SudimeTools.ceId(stack))) {
                continue;
            }
            int moved = insertInto(drawer, preferred, stack);
            if (moved > 0) {
                total += moved;
                int remaining = stack.getAmount() - moved;
                if (remaining <= 0) {
                    contents[i] = null;
                } else {
                    stack.setAmount(remaining);
                }
            }
        }
        player.getInventory().setStorageContents(contents);
        return total;
    }

    /** Insère une pile dans le compartiment le plus adapté. Renvoie la quantité déplacée. */
    private int insertInto(Drawer drawer, int preferred, ItemStack stack) {
        int index = findSlot(drawer, preferred, stack);
        if (index < 0) {
            return 0;
        }
        int max = Math.max(1, stack.getMaxStackSize()) * STACKS_PER_SLOT;
        int space = max - drawer.counts[index];
        int moved = Math.min(space, stack.getAmount());
        if (moved <= 0) {
            return 0;
        }
        if (drawer.items[index] == null) {
            ItemStack template = stack.clone();
            template.setAmount(1);
            drawer.items[index] = template;
        }
        drawer.counts[index] = Math.max(0, drawer.counts[index]) + moved;
        return moved;
    }

    private int findSlot(Drawer drawer, int preferred, ItemStack stack) {
        // 1) Un compartiment débloqué qui contient déjà ce type d'item.
        for (int i = 0; i < drawer.unlocked; i++) {
            ItemStack item = drawer.items[i];
            if (item != null && item.isSimilar(stack) && drawer.counts[i] > 0) {
                int max = Math.max(1, item.getMaxStackSize()) * STACKS_PER_SLOT;
                if (drawer.counts[i] < max) {
                    return i;
                }
            }
        }
        // 2) Le compartiment cliqué s'il est vide.
        if (preferred >= 0 && preferred < drawer.unlocked && drawer.items[preferred] == null) {
            return preferred;
        }
        // 3) Le premier compartiment vide.
        for (int i = 0; i < drawer.unlocked; i++) {
            if (drawer.items[i] == null) {
                return i;
            }
        }
        return -1;
    }

    private void extract(Player player, Block block, Drawer drawer, int slot) {
        if (slot < 0 || slot >= drawer.unlocked
                || drawer.items[slot] == null || drawer.counts[slot] <= 0) {
            player.sendActionBar(Component.text("Compartiment vide", NamedTextColor.GRAY));
            return;
        }
        ItemStack template = drawer.items[slot];
        int stackSize = Math.max(1, template.getMaxStackSize());
        int take = player.isSneaking() ? drawer.counts[slot] : Math.min(drawer.counts[slot], stackSize);
        ItemStack give = template.clone();
        give.setAmount(take);

        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(give);
        int notGiven = 0;
        for (ItemStack left : leftovers.values()) {
            notGiven += left.getAmount();
        }
        int given = take - notGiven;
        if (given <= 0) {
            player.sendActionBar(Component.text("Inventaire plein", NamedTextColor.RED));
            return;
        }
        drawer.counts[slot] -= given;
        if (drawer.counts[slot] <= 0) {
            drawer.counts[slot] = 0;
            drawer.items[slot] = null;
        }
        save();
        refresh(block);
        player.playSound(block.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.7f, 1.4f);
    }

    private void upgrade(Player player, Block block, Drawer drawer) {
        if (drawer.unlocked >= MAX_SLOTS) {
            player.sendActionBar(Component.text("Déjà 4/4 compartiments", NamedTextColor.GOLD));
            return;
        }
        drawer.unlocked++;
        ItemStack hand = player.getInventory().getItemInMainHand();
        int remaining = hand.getAmount() - 1;
        if (remaining <= 0) {
            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        } else {
            hand.setAmount(remaining);
            player.getInventory().setItemInMainHand(hand);
        }
        save();
        refresh(block);
        player.sendActionBar(Component.text("Drawer amélioré : "
                + drawer.unlocked + "/" + MAX_SLOTS + " compartiments", NamedTextColor.GREEN));
        player.playSound(block.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 1.3f);
    }

    // ------------------------------------------------------------------
    // Casse / explosions / pistons
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(CustomBlockBreakEvent event) {
        if (!DRAWER_ID.equals(event.customBlock().id().asString())) {
            return;
        }
        Block block = event.bukkitBlock();
        String key = key(block);
        Drawer drawer = drawers.remove(key);
        removeHologram(block, key);
        if (drawer == null) {
            save();
            return;
        }
        Location drop = block.getLocation().add(0.5, 0.5, 0.5);
        for (int i = 0; i < MAX_SLOTS; i++) {
            ItemStack template = drawer.items[i];
            if (template == null || drawer.counts[i] <= 0) {
                continue;
            }
            int stackSize = Math.max(1, template.getMaxStackSize());
            int count = drawer.counts[i];
            while (count > 0) {
                ItemStack stack = template.clone();
                int amount = Math.min(count, stackSize);
                stack.setAmount(amount);
                block.getWorld().dropItemNaturally(drop, stack);
                count -= amount;
            }
        }
        for (int i = 1; i < drawer.unlocked; i++) {
            block.getWorld().dropItemNaturally(drop, upgradeItem());
        }
        save();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isDrawer);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isDrawer);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(this::isDrawer)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(this::isDrawer)) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------
    // Hologramme au-dessus du drawer
    // ------------------------------------------------------------------

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (String key : drawers.keySet()) {
            Location location = parseKey(key);
            if (location == null) {
                continue;
            }
            if (location.getWorld().equals(event.getWorld())
                    && location.getChunk().equals(event.getChunk())) {
                refresh(location.getBlock());
            }
        }
    }

    private void refreshLoaded() {
        for (String key : drawers.keySet()) {
            Location location = parseKey(key);
            if (location == null) {
                continue;
            }
            if (location.getWorld() != null
                    && location.getWorld().isChunkLoaded(location.getBlockX() >> 4,
                    location.getBlockZ() >> 4)) {
                refresh(location.getBlock());
            }
        }
    }

    private void refresh(Block block) {
        World world = block.getWorld();
        String key = key(block);
        removeHologram(block, key);
        Drawer drawer = drawers.get(key);
        if (drawer == null || (drawer.unlocked <= 1 && drawer.isEmpty())) {
            return;
        }
        if (!world.isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
            return;
        }
        Location center = frontCenter(block);
        world.spawn(center, TextDisplay.class, display -> {
            display.text(buildText(drawer));
            display.setBillboard(Display.Billboard.CENTER);
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            display.setShadowed(true);
            display.setSeeThrough(false);
            display.setDefaultBackground(false);
            display.setBackgroundColor(Color.fromARGB(120, 0, 0, 0));
            display.setLineWidth(220);
            display.setViewRange(0.4f);
            display.setPersistent(false);
            display.getScoreboardTags().add(HOLO_TAG);
            display.getPersistentDataContainer().set(holoKey, PersistentDataType.STRING, key);
        });
    }

    private void removeHologram(Block block, String key) {
        World world = block.getWorld();
        if (!world.isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
            return;
        }
        Location center = frontCenter(block);
        for (Entity entity : world.getNearbyEntities(center, 1.0, 1.2, 1.0)) {
            if (!(entity instanceof TextDisplay)) {
                continue;
            }
            if (!entity.getScoreboardTags().contains(HOLO_TAG)) {
                continue;
            }
            if (key.equals(entity.getPersistentDataContainer()
                    .get(holoKey, PersistentDataType.STRING))) {
                entity.remove();
            }
        }
    }

    /** Point au centre de la face avant du drawer (là où est affiché l'hologramme). */
    private Location frontCenter(Block block) {
        Location center = block.getLocation().add(0.5, 0.55, 0.5);
        try {
            ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
            if (state != null) {
                Property<Direction> property = state.getProperty("facing");
                if (property != null) {
                    Direction facing = state.get(property);
                    if (facing != null) {
                        switch (facing) {
                            case NORTH -> center.add(0, 0, -0.55);
                            case SOUTH -> center.add(0, 0, 0.55);
                            case EAST -> center.add(0.55, 0, 0);
                            case WEST -> center.add(-0.55, 0, 0);
                            default -> {
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // Pas de facing lisible : hologramme au-dessus du bloc.
            center.add(0, 0.65, 0);
        }
        return center;
    }

    private Component buildText(Drawer drawer) {
        Component text = Component.text("Drawer Sudime ", NamedTextColor.LIGHT_PURPLE)
                .append(Component.text(drawer.unlocked + "/" + MAX_SLOTS, NamedTextColor.WHITE));
        for (int i = 0; i < drawer.unlocked; i++) {
            text = text.append(Component.newline());
            if (drawer.items[i] == null || drawer.counts[i] <= 0) {
                text = text.append(Component.text("— vide —", NamedTextColor.DARK_GRAY));
            } else {
                text = text.append(itemName(drawer.items[i]).colorIfAbsent(NamedTextColor.WHITE))
                        .append(Component.text(" ×" + drawer.counts[i], NamedTextColor.GRAY));
            }
        }
        return text;
    }

    private Component itemName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            Component name = meta.displayName();
            if (name != null) {
                return name;
            }
        }
        return Component.translatable(item.getType().translationKey());
    }

    // ------------------------------------------------------------------
    // Items / debug
    // ------------------------------------------------------------------

    private ItemStack drawerItem() {
        try {
            var definition = CraftEngineItems.byId(DRAWER_ID);
            if (definition != null) {
                return definition.buildBukkitItem();
            }
        } catch (Throwable ignored) {
            // CraftEngine pas encore prêt : on retombe sur un bloc vanilla.
        }
        return new ItemStack(Material.NOTE_BLOCK);
    }

    private ItemStack upgradeItem() {
        try {
            var definition = CraftEngineItems.byId(UPGRADE_ID);
            if (definition != null) {
                return definition.buildBukkitItem();
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return new ItemStack(Material.NETHERITE_INGOT);
    }

    /** Donne un drawer et les 3 upgrades (commande de test /debug). */
    public void giveKit(Player player) {
        ItemStack drawer = drawerItem();
        ItemStack upgrade = upgradeItem();
        upgrade.setAmount(MAX_SLOTS - 1);
        for (ItemStack leftover : player.getInventory().addItem(drawer, upgrade).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
        player.sendMessage(Component.text("Drawer en Sudime + 3 upgrades donnés.",
                NamedTextColor.GREEN));
    }
}
