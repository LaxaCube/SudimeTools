package ch.laxacube.sudimetools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.Chunk;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Comportements custom des items en Sudime créés dans CraftEngine.
 * <p>
 * Les items sont reconnus grâce au composant custom_data posé par CraftEngine :
 * {"craftengine:id":"sudime:sudime_pickaxe"}.
 */
public final class SudimeTools extends JavaPlugin implements Listener {

    private static final NamespacedKey CE_ID = new NamespacedKey("craftengine", "id");

    private static final String SHOVEL = "sudime:sudime_shovel";
    private static final String AXE = "sudime:sudime_axe";
    private static final String HAMMER = "sudime:sudime_hammer";
    private static final String HOE = "sudime:sudime_hoe";
    private static final String MAGNET = "sudime:sudime_magnet";
    private static final String DETECTOR = "sudime:sudime_chest_detector";
    private static final String TURBO = "sudime:turbo_farmland";

    /** Blocs que la pelle casse instantanément. */
    private static final Set<Material> SHOVEL_BLOCKS = EnumSet.of(
            Material.DIRT, Material.GRASS_BLOCK, Material.PODZOL, Material.COARSE_DIRT,
            Material.ROOTED_DIRT, Material.MYCELIUM, Material.FARMLAND, Material.DIRT_PATH,
            Material.SAND, Material.RED_SAND, Material.SUSPICIOUS_SAND,
            Material.GRAVEL, Material.SUSPICIOUS_GRAVEL, Material.CLAY,
            Material.SOUL_SAND, Material.SOUL_SOIL, Material.SNOW, Material.SNOW_BLOCK,
            Material.POWDER_SNOW, Material.MUD, Material.MUDDY_MANGROVE_ROOTS, Material.MOSS_BLOCK
    );

    /** Cultures récoltables / replantables par la houe. */
    private static final Set<Material> CROPS = EnumSet.of(
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS, Material.NETHER_WART
    );

    /** Cultures qui poussent sur la Terre Turbo. */
    private static final Set<Material> TURBO_CROPS = EnumSet.of(
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
            Material.NETHER_WART, Material.PUMPKIN_STEM, Material.MELON_STEM,
            Material.SWEET_BERRY_BUSH, Material.TORCHFLOWER_CROP, Material.PITCHER_CROP
    );

    /**
     * Probabilité par passe de 10 ticks de lancer un random tick supplémentaire.
     * 31 random ticks en plus du tick vanilla = 32x au total.
     */
    private static final double TURBO_CHANCE = 1.0 - Math.pow(
            1.0 - 31.0 * 3.0 / 4096.0, 10.0);

    /** Conteneurs comptés par le détecteur de coffres. */
    private static final Set<Material> CHESTS = EnumSet.of(
            Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL
    );

    private static final int MAX_TREE = 400;

    /** Clé du modificateur de vitesse de minage appliqué au marteau. */
    private static final NamespacedKey HAMMER_SLOW_KEY =
            new NamespacedKey("sudimetools", "hammer_slow");

    private SudimeDrawers drawers;

    private final Set<UUID> activeMagnets = new HashSet<>();
    private final Set<Location> busy = new HashSet<>();
    private final Set<Location> turboFarmland = new HashSet<>();
    private File turboFile;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new HeartsExpansion(this).register();
            getLogger().info("Placeholder %sudime_hearts% enregistré.");
        }
        if (getServer().getPluginManager().getPlugin("CraftEngine") != null) {
            drawers = new SudimeDrawers(this);
            drawers.enable();
            getLogger().info("Drawers en Sudime : bloc à 4 compartiments enregistré.");
        }
        loadTurbo();
        // Aimant : aspiration toutes les 0,5 s.
        getServer().getScheduler().scheduleSyncRepeatingTask(this, this::tickMagnets, 10L, 10L);
        // Terre Turbo : random ticks supplémentaires toutes les 0,5 s.
        getServer().getScheduler().scheduleSyncRepeatingTask(this, this::tickTurboFarmland, 10L, 10L);
        // Marteau : ralentissement de minage réappliqué toutes les 0,5 s.
        getServer().getScheduler().scheduleSyncRepeatingTask(this, this::tickHammerSlowdown, 10L, 10L);
        getLogger().info("SudimeTools actif — outils Sudime (CraftEngine) chargés.");
    }

    @Override
    public void onDisable() {
        if (drawers != null) {
            drawers.disable();
        }
        saveTurbo();
        activeMagnets.clear();
    }

    // ------------------------------------------------------------------
    // Détection des items
    // ------------------------------------------------------------------

    /** Renvoie l'id CraftEngine de l'item (ex: sudime:sudime_pickaxe) ou null. */
    static String ceId(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        // 1) PDC Bukkit sur le composant custom_data.
        String id = meta.getPersistentDataContainer().get(CE_ID, PersistentDataType.STRING);
        if (id != null) {
            return id;
        }
        // 2) SNBT complet des composants (contourne les limitations du PDC).
        try {
            id = extractCeId(meta.getAsComponentString());
            if (id != null) {
                return id;
            }
        } catch (Throwable ignored) {
            // méthode indisponible selon la version : on passe au NBT binaire
        }
        // 3) NBT binaire, dernier recours.
        try {
            String raw = new String(item.serializeAsBytes(), StandardCharsets.ISO_8859_1);
            int key = raw.indexOf("craftengine:id");
            int start = key < 0 ? -1 : raw.indexOf("sudime:", key);
            if (start > 0) {
                int end = start;
                while (end < raw.length() && raw.charAt(end) > ' ' && raw.charAt(end) < '~') {
                    end++;
                }
                return raw.substring(start, end);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return null;
    }

    /** Extrait la valeur de "craftengine:id" depuis un SNBT de composants. */
    private static String extractCeId(String snbt) {
        if (snbt == null) {
            return null;
        }
        int key = snbt.indexOf("craftengine:id");
        if (key < 0) {
            return null;
        }
        int colon = snbt.indexOf(':', key + "craftengine:id".length());
        if (colon < 0) {
            return null;
        }
        int i = colon + 1;
        while (i < snbt.length() && Character.isWhitespace(snbt.charAt(i))) {
            i++;
        }
        if (i >= snbt.length()) {
            return null;
        }
        char quote = snbt.charAt(i);
        if (quote == '"' || quote == '\'') {
            int close = snbt.indexOf(quote, i + 1);
            return close > i ? snbt.substring(i + 1, close) : null;
        }
        int end = i;
        while (end < snbt.length() && ",}]".indexOf(snbt.charAt(end)) < 0) {
            end++;
        }
        String value = snbt.substring(i, end).trim();
        return value.isEmpty() ? null : value;
    }

    private String heldId(Player player) {
        return ceId(player.getInventory().getItemInMainHand());
    }

    private ItemStack itemInHand(Player player, EquipmentSlot hand) {
        return hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
    }

    private void setItemInHand(Player player, EquipmentSlot hand, ItemStack item) {
        if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(item);
        } else {
            player.getInventory().setItemInMainHand(item);
        }
    }

    /** Renvoie la main qui tient la Magnette, ou null. */
    private EquipmentSlot magnetHand(Player player) {
        if (MAGNET.equals(ceId(player.getInventory().getItemInMainHand()))) {
            return EquipmentSlot.HAND;
        }
        if (MAGNET.equals(ceId(player.getInventory().getItemInOffHand()))) {
            return EquipmentSlot.OFF_HAND;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Durabilité
    // ------------------------------------------------------------------

    private void damageTool(Player player, ItemStack tool, int amount) {
        damageTool(player, tool, amount, EquipmentSlot.HAND);
    }

    private void damageTool(Player player, ItemStack tool, int amount, EquipmentSlot hand) {
        if (tool == null || amount <= 0) {
            return;
        }
        ItemMeta meta = tool.getItemMeta();
        if (!(meta instanceof Damageable damageable) || !damageable.hasMaxDamage()) {
            // Item sans composant de durabilité (ex. pièce incassable) : rien à user.
            return;
        }
        int max = damageable.getMaxDamage();
        if (max <= 0) {
            return;
        }
        int next = damageable.getDamage() + amount;
        if (next >= max) {
            setItemInHand(player, hand, new ItemStack(Material.AIR));
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
            player.sendActionBar(Component.text("Ton outil en Sudime s'est brisé !", NamedTextColor.RED));
            return;
        }
        damageable.setDamage(next);
        tool.setItemMeta(meta);
        setItemInHand(player, hand, tool);
    }

    // ------------------------------------------------------------------
    // Pelle : cassage instantané
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStartDigging(BlockDamageEvent event) {
        Player player = event.getPlayer();
        String id = heldId(player);
        if (id == null) {
            return;
        }
        if (!SHOVEL.equals(id)) {
            return;
        }
        Block block = event.getBlock();
        if (block.isEmpty() || block.isLiquid() || block.getType().getHardness() < 0) {
            return;
        }
        if (!SHOVEL_BLOCKS.contains(block.getType())) {
            return;
        }
        final ItemStack tool = player.getInventory().getItemInMainHand();
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline()) {
                return;
            }
            Location loc = block.getLocation();
            if (block.isEmpty() || busy.contains(loc)) {
                return;
            }
            busy.add(loc);
            boolean broken = block.breakNaturally(tool);
            busy.remove(loc);
            if (broken) {
                damageTool(player, tool, 1);
            }
        });
    }

    // ------------------------------------------------------------------
    // Hache : abat l'arbre entier / Marteau : 3x3
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        String id = heldId(player);
        if (id == null) {
            return;
        }
        Block origin = event.getBlock();
        if (busy.contains(origin.getLocation())) {
            return;
        }
        ItemStack tool = player.getInventory().getItemInMainHand();

        if (AXE.equals(id)) {
            if (isLog(origin.getType()) && !player.isSneaking()) {
                chopTree(player, tool, origin);
            }
        } else if (HAMMER.equals(id)) {
            if (origin.getType().getHardness() < 0) {
                return;
            }
            breakHammer(player, tool, origin);
        }
    }

    /**
     * Marteau : minage volontairement lent via l'attribut de vitesse, sans jamais
     * annuler le cassage (annuler remet la progression du bloc à zéro côté client).
     */
    private void updateHammerSlowdown(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.PLAYER_BLOCK_BREAK_SPEED);
        if (attribute == null) {
            return;
        }
        boolean applied = false;
        for (AttributeModifier modifier : attribute.getModifiers()) {
            if (HAMMER_SLOW_KEY.equals(modifier.getKey())) {
                applied = true;
                break;
            }
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        boolean hammer = held.getType() == Material.NETHERITE_PICKAXE && HAMMER.equals(ceId(held));
        if (hammer == applied) {
            return;
        }
        if (hammer) {
            attribute.addModifier(new AttributeModifier(HAMMER_SLOW_KEY, -0.8,
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        } else {
            for (AttributeModifier modifier : new ArrayList<>(attribute.getModifiers())) {
                if (HAMMER_SLOW_KEY.equals(modifier.getKey())) {
                    attribute.removeModifier(modifier);
                }
            }
        }
    }

    private void tickHammerSlowdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateHammerSlowdown(player);
        }
    }

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline()) {
                updateHammerSlowdown(player);
            }
        });
    }

    private boolean isLog(Material material) {
        String name = material.name();
        return name.endsWith("_LOG") || name.endsWith("_WOOD") || name.endsWith("_HYPHAE");
    }

    private void chopTree(Player player, ItemStack tool, Block origin) {
        Set<Location> seen = new HashSet<>();
        Set<Block> logs = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        queue.add(origin);
        seen.add(origin.getLocation());

        while (!queue.isEmpty() && logs.size() < MAX_TREE) {
            Block current = queue.poll();
            if (!isLog(current.getType())) {
                continue;
            }
            logs.add(current);
            for (BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.DOWN,
                    BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                Block next = current.getRelative(face);
                if (isLog(next.getType()) && seen.add(next.getLocation())) {
                    queue.add(next);
                }
            }
        }

        int extra = 0;
        for (Block log : logs) {
            if (log.equals(origin) || busy.contains(log.getLocation()) || log.isEmpty()) {
                continue;
            }
            busy.add(log.getLocation());
            if (log.breakNaturally(tool)) {
                extra++;
            }
            busy.remove(log.getLocation());
        }
        if (extra > 0) {
            damageTool(player, tool, 1);
        }
    }

    private void breakHammer(Player player, ItemStack tool, Block origin) {
        BlockFace face = facingFace(player);
        List<Block> targets = new ArrayList<>(9);
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                Block block = switch (face) {
                    case UP, DOWN -> origin.getRelative(a, 0, b);
                    case NORTH, SOUTH -> origin.getRelative(a, b, 0);
                    default -> origin.getRelative(0, b, a);
                };
                targets.add(block);
            }
        }
        int extra = 0;
        for (Block block : targets) {
            if (block.equals(origin) || block.isEmpty() || block.getType().getHardness() < 0) {
                continue;
            }
            if (busy.contains(block.getLocation())) {
                continue;
            }
            busy.add(block.getLocation());
            boolean broken = block.breakNaturally(tool);
            busy.remove(block.getLocation());
            if (broken) {
                extra++;
            }
        }
        if (extra > 0) {
            damageTool(player, tool, 1);
        }
    }

    private BlockFace facingFace(Player player) {
        float pitch = player.getLocation().getPitch();
        if (pitch < -55f) {
            return BlockFace.UP;
        }
        if (pitch > 55f) {
            return BlockFace.DOWN;
        }
        float yaw = (player.getLocation().getYaw() % 360f + 360f) % 360f;
        if (yaw < 45f || yaw >= 315f) {
            return BlockFace.SOUTH;
        }
        if (yaw < 135f) {
            return BlockFace.WEST;
        }
        if (yaw < 225f) {
            return BlockFace.NORTH;
        }
        return BlockFace.EAST;
    }

    // ------------------------------------------------------------------
    // Houe : récolte + replante en 3x3 / Détecteur / Aimant
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.PHYSICAL) {
            Block clicked = event.getClickedBlock();
            if (clicked != null && clicked.getType() == Material.FARMLAND
                    && turboFarmland.contains(clicked.getLocation())) {
                // Terre Turbo : le trampling ne doit pas la transformer en terre.
                event.setCancelled(true);
                wetFarmland(clicked);
            }
            return;
        }
        EquipmentSlot hand = event.getHand();
        if (hand == null) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack tool = itemInHand(player, hand);
        String id = ceId(tool);
        if (id == null) {
            return;
        }

        if (MAGNET.equals(id)) {
            if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                event.setCancelled(true);
                if (player.isSneaking()) {
                    toggleMagnet(player);
                }
            }
            return;
        }

        if (hand != EquipmentSlot.HAND) {
            return;
        }

        if (DETECTOR.equals(id)) {
            if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                event.setCancelled(true);
                if (!player.isSneaking()) {
                    detectChests(player, tool);
                }
            }
            return;
        }

        if (HOE.equals(id)) {
            if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
                Block clicked = event.getClickedBlock();
                if (isMatureCrop(clicked)) {
                    event.setCancelled(true);
                    harvest3x3(player, tool, clicked);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityTrample(EntityInteractEvent event) {
        Block block = event.getBlock();
        if (block.getType() == Material.FARMLAND
                && turboFarmland.contains(block.getLocation())) {
            // Mobs : même protection que pour les joueurs.
            event.setCancelled(true);
            wetFarmland(block);
        }
    }

    private boolean isMatureCrop(Block block) {
        if (!CROPS.contains(block.getType())) {
            return false;
        }
        return block.getBlockData() instanceof Ageable ageable && ageable.getAge() >= ageable.getMaximumAge();
    }

    private void harvest3x3(Player player, ItemStack tool, Block center) {
        Material crop = center.getType();
        int harvested = 0;
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                Block block = center.getRelative(x, 0, z);
                if (block.getType() != crop || !isMatureCrop(block)) {
                    continue;
                }
                Location loc = block.getLocation();
                if (busy.contains(loc)) {
                    continue;
                }
                busy.add(loc);
                block.breakNaturally(tool);
                block.setType(crop, false);
                if (block.getBlockData() instanceof Ageable ageable) {
                    ageable.setAge(0);
                    block.setBlockData(ageable, false);
                }
                busy.remove(loc);
                harvested++;
            }
        }
        if (harvested > 0) {
            damageTool(player, tool, 1);
            player.sendActionBar(Component.text(harvested + " culture(s) récoltée(s) et replantée(s)",
                    NamedTextColor.GREEN));
        }
    }

    private void detectChests(Player player, ItemStack tool) {
        Chunk chunk = player.getChunk();
        int count = 0;
        for (BlockState state : chunk.getTileEntities()) {
            if (CHESTS.contains(state.getType())) {
                count++;
            }
        }
        player.sendMessage(Component.text("Coffres dans ce chunk : ", NamedTextColor.AQUA)
                .append(Component.text(count, NamedTextColor.WHITE)));
        damageTool(player, tool, 1);
    }

    private void toggleMagnet(Player player) {
        UUID uuid = player.getUniqueId();
        if (activeMagnets.remove(uuid)) {
            player.sendActionBar(Component.text("Magnette désactivée", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1f, 1.4f);
        } else {
            activeMagnets.add(uuid);
            player.sendActionBar(Component.text("Magnette activée — rayon de 20 blocs", NamedTextColor.AQUA));
            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.4f);
        }
    }

    private void tickMagnets() {
        if (activeMagnets.isEmpty()) {
            return;
        }
        for (UUID uuid : new ArrayList<>(activeMagnets)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                activeMagnets.remove(uuid);
                continue;
            }
            EquipmentSlot hand = magnetHand(player);
            if (hand == null) {
                activeMagnets.remove(uuid);
                player.sendActionBar(Component.text("Magnette désactivée (item retiré)", NamedTextColor.RED));
                continue;
            }
            ItemStack tool = itemInHand(player, hand);
            Location center = player.getLocation().add(0, 1, 0);
            boolean pulled = false;
            for (Entity entity : player.getNearbyEntities(20, 20, 20)) {
                if (!(entity instanceof Item item)) {
                    continue;
                }
                Vector direction = center.toVector().subtract(item.getLocation().toVector());
                if (direction.lengthSquared() < 1.0) {
                    item.teleport(center);
                    pulled = true;
                    continue;
                }
                item.setVelocity(direction.normalize().multiply(1.4));
                pulled = true;
            }
            if (pulled) {
                damageTool(player, tool, 1, hand);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        activeMagnets.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------
    // Terre Turbo : cultures 8x plus rapides
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTurboPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (TURBO.equals(ceId(event.getItemInHand()))) {
            if (block.getType() != Material.FARMLAND) {
                block.setType(Material.FARMLAND, false);
            }
            wetFarmland(block);
            turboFarmland.add(block.getLocation());
            saveTurbo();
            return;
        }
        // Une terre normale posée sur un ancien emplacement Turbo annule le marqueur.
        if (block.getType() == Material.FARMLAND && turboFarmland.remove(block.getLocation())) {
            saveTurbo();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTurboBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() == Material.FARMLAND
                && turboFarmland.remove(event.getBlock().getLocation())) {
            saveTurbo();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTurboFade(BlockFadeEvent event) {
        if (event.getBlock().getType() == Material.FARMLAND
                && turboFarmland.remove(event.getBlock().getLocation())) {
            saveTurbo();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        removeTurboBlocks(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        removeTurboBlocks(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        removeTurboBlocks(event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        removeTurboBlocks(event.getBlocks());
    }

    private void removeTurboBlocks(List<Block> blocks) {
        boolean changed = false;
        for (Block block : blocks) {
            if (block.getType() == Material.FARMLAND) {
                changed |= turboFarmland.remove(block.getLocation());
            }
        }
        if (changed) {
            saveTurbo();
        }
    }

    private void wetFarmland(Block block) {
        if (block.getType() != Material.FARMLAND) {
            return;
        }
        if (block.getBlockData() instanceof Farmland farmland && farmland.getMoisture() != 7) {
            farmland.setMoisture(7);
            block.setBlockData(farmland, false);
        }
    }

    private void tickTurboFarmland() {
        if (turboFarmland.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (Iterator<Location> iterator = turboFarmland.iterator(); iterator.hasNext(); ) {
            Location location = iterator.next();
            World world = location.getWorld();
            if (world == null) {
                iterator.remove();
                changed = true;
                continue;
            }
            if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                continue;
            }
            Block farmland = location.getBlock();
            if (farmland.getType() != Material.FARMLAND) {
                iterator.remove();
                changed = true;
                continue;
            }
            wetFarmland(farmland);
            Block crop = farmland.getRelative(BlockFace.UP);
            if (!TURBO_CROPS.contains(crop.getType())) {
                continue;
            }
            if (!(crop.getBlockData() instanceof Ageable ageable)
                    || ageable.getAge() >= ageable.getMaximumAge()) {
                continue;
            }
            if (ThreadLocalRandom.current().nextDouble() < TURBO_CHANCE) {
                crop.randomTick();
            }
        }
        if (changed) {
            saveTurbo();
        }
    }

    private void loadTurbo() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        turboFile = new File(getDataFolder(), "turbo_farmland.yml");
        turboFarmland.clear();
        if (!turboFile.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(turboFile);
        for (String raw : yaml.getStringList("locations")) {
            Location location = parseTurboLocation(raw);
            if (location != null) {
                turboFarmland.add(location);
            }
        }
        getLogger().info("Terre Turbo : " + turboFarmland.size() + " bloc(s) chargé(s).");
    }

    private void saveTurbo() {
        if (turboFile == null) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        List<String> locations = new ArrayList<>(turboFarmland.size());
        for (Location location : turboFarmland) {
            String formatted = formatTurboLocation(location);
            if (formatted != null) {
                locations.add(formatted);
            }
        }
        yaml.set("locations", locations);
        try {
            yaml.save(turboFile);
        } catch (IOException exception) {
            getLogger().warning("Impossible de sauvegarder la Terre Turbo : " + exception.getMessage());
        }
    }

    private String formatTurboLocation(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return null;
        }
        return world.getName() + ";" + location.getBlockX()
                + ";" + location.getBlockY() + ";" + location.getBlockZ();
    }

    private Location parseTurboLocation(String raw) {
        String[] parts = raw.split(";");
        if (parts.length != 4) {
            return null;
        }
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) {
            return null;
        }
        try {
            return new Location(world, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Commande d'administration / debug
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "id" : args[0].toLowerCase();
        if ("id".equals(sub)) {
            Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1])
                    : (sender instanceof Player player ? player : null);
            if (target == null) {
                sender.sendMessage(Component.text("Usage : /sudimetools id <joueur>", NamedTextColor.GRAY));
                return true;
            }
            List<ItemStack> found = new ArrayList<>();
            for (ItemStack stack : target.getInventory().getContents()) {
                if (ceId(stack) != null) {
                    found.add(stack);
                }
            }
            if (found.isEmpty()) {
                sender.sendMessage(Component.text(target.getName()
                        + " : aucun item CraftEngine dans l'inventaire", NamedTextColor.GRAY));
                return true;
            }
            for (ItemStack stack : found) {
                ItemMeta meta = stack.getItemMeta();
                sender.sendMessage(Component.text(stack.getType() + " x" + stack.getAmount()
                        + " | CE id : " + ceId(stack)
                        + " | CMD : " + (meta != null && meta.hasCustomModelData()
                        ? meta.getCustomModelData() : "aucun"), NamedTextColor.YELLOW));
            }
            return true;
        }
        if ("selftest".equals(sub)) {
            ItemStack probe = new ItemStack(Material.IRON_SWORD);
            ItemMeta meta = probe.getItemMeta();
            meta.getPersistentDataContainer().set(CE_ID, PersistentDataType.STRING, "sudime:sudime_pickaxe");
            probe.setItemMeta(meta);
            String viaPdc = meta.getPersistentDataContainer().get(CE_ID, PersistentDataType.STRING);
            String viaSnbt = extractCeId(
                    "{custom_data:{craftengine:id:\"sudime:sudime_axe\"},custom_model_data:10000}");
            String viaItem = ceId(probe);
            sender.sendMessage(Component.text("selftest pdc=" + viaPdc
                    + " snbt=" + viaSnbt + " item=" + viaItem, NamedTextColor.AQUA));
            for (World world : Bukkit.getWorlds()) {
                for (Entity entity : world.getEntitiesByClass(Item.class)) {
                    if (!entity.getScoreboardTags().contains("sudimetest")) {
                        continue;
                    }
                    ItemStack dropped = ((Item) entity).getItemStack();
                    sender.sendMessage(Component.text("item droppé " + dropped.getType()
                            + " -> " + ceId(dropped), NamedTextColor.GRAY));
                }
            }
            return true;
        }
        if ("dump".equals(sub)) {
            Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1])
                    : (sender instanceof Player player ? player : null);
            if (target == null) {
                sender.sendMessage(Component.text("Usage : /sudimetools dump <joueur>", NamedTextColor.GRAY));
                return true;
            }
            for (ItemStack stack : target.getInventory().getContents()) {
                if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
                    continue;
                }
                ItemMeta meta = stack.getItemMeta();
                StringBuilder sb = new StringBuilder();
                sb.append(stack.getType()).append(" PDC[");
                for (NamespacedKey key : meta.getPersistentDataContainer().getKeys()) {
                    sb.append(key).append('=');
                    String value = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
                    sb.append(value == null ? "?" : value).append(';');
                }
                sb.append("] CMD=").append(meta.hasCustomModelData() ? meta.getCustomModelData() : "none");
                try {
                    String snbt = meta.getAsComponentString();
                    sb.append(" | SNBT=").append(snbt.length() > 220 ? snbt.substring(0, 220) : snbt);
                } catch (Throwable ignored) {
                    sb.append(" | SNBT=indisponible");
                }
                sender.sendMessage(Component.text(sb.toString(), NamedTextColor.YELLOW));
            }
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Commande joueur uniquement.", NamedTextColor.RED));
            return true;
        }
        if ("drawer".equals(sub)) {
            if (drawers == null) {
                player.sendMessage(Component.text("CraftEngine absent : drawers indisponibles.",
                        NamedTextColor.RED));
                return true;
            }
            drawers.giveKit(player);
            return true;
        }
        if ("magnet".equals(sub)) {
            toggleMagnet(player);
            return true;
        }
        player.sendMessage(Component.text("Usage : /sudimetools <id [joueur]|dump|selftest|magnet|drawer>", NamedTextColor.GRAY));
        return true;
    }
}
