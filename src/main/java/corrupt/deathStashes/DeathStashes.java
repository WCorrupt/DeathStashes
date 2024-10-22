package corrupt.deathStashes;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class DeathStashes extends JavaPlugin implements Listener {
    @EventHandler
    public void onInventoryClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        Player player = (Player) event.getPlayer();
        Inventory inventory = event.getInventory();
        if (event.getView().getTitle().equals("Death Stash")) {
            getLogger().info("Saving death stash for " + player.getName());
            String uniqueId = getUniqueIdFromItem(player.getInventory().getItemInMainHand());
            if (uniqueId != null && deathsConfig.contains("deaths." + uniqueId)) {
                List<ItemStack> items = Arrays.stream(inventory.getContents()).filter(Objects::nonNull).collect(Collectors.toList());
                deathsConfig.set("deaths." + uniqueId + ".items", items);
                saveDeathsConfig();
                getLogger().info("Death stash saved for unique ID: " + uniqueId);
            }
        }
    }

    private String getUniqueIdFromItem(ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        NamespacedKey key = new NamespacedKey(this, "deathstash");
        return meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private String generateUniqueId() {
        StringBuilder uniqueId = new StringBuilder(10);
        Random random = new Random();
        for (int i = 0; i < 10; i++) {
            uniqueId.append(CHARACTERS.charAt(random.nextInt(CHARACTERS.length())));
        }
        return uniqueId.toString();
    }

    private File deathsFile;
    private FileConfiguration deathsConfig;

    @Override
    public void onEnable() {
        deathsFile = new File(getDataFolder(), "deaths.yml");
        if (!deathsFile.exists()) {
            if (!deathsFile.getParentFile().mkdirs()) {
                getLogger().warning("Failed to create parent directories for deaths.yml");
            }
            try {
                if (!deathsFile.createNewFile()) {
                    getLogger().warning("Failed to create deaths.yml");
                }
            } catch (IOException e) {
                getLogger().severe("An error occurred: " + e.getMessage());
            }
        }
        deathsConfig = YamlConfiguration.loadConfiguration(deathsFile);
        loadAllDeathStashes();

        Bukkit.getPluginManager().registerEvents(this, this);

        this.getCommand("expirestash").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Only players can execute this command.");
                return true;
            }
            Player player = (Player) sender;
            if (!player.isOp()) {
                player.sendMessage("You do not have permission to use this command.");
                return true;
            }
            ItemStack itemInHand = player.getInventory().getItemInMainHand();
            if (itemInHand.getType() == Material.AIR || itemInHand.getType() != Material.PLAYER_HEAD) {
                player.sendMessage("You must be holding a death stash to use this command.");
                return true;
            }
            String uniqueId = getUniqueIdFromItem(itemInHand);
            if (uniqueId == null || !deathsConfig.contains("deaths." + uniqueId)) {
                player.sendMessage("This item is not a valid death stash.");
                return true;
            }
            deathsConfig.set("deaths." + uniqueId + ".expired", true);
            deathsConfig.set("deaths." + uniqueId + ".items", null);
            deathsConfig.set("deaths." + uniqueId + ".time", null);
            saveDeathsConfig();
            player.sendMessage("The death stash has been expired.");
            return true;
        });

        new BukkitRunnable() {
            @Override
            public void run() {
                cleanupExpiredStashes();
            }
        }.runTaskTimer(this, 0, 20 * 60 * 10);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        String uniqueId = generateUniqueId();
        getLogger().info("Death stash created for " + event.getEntity().getName());
        Player deceased = event.getEntity();
        Player killer = deceased.getKiller();
        String killerName = (killer != null) ? killer.getName() : "Unknown";

        ItemStack playerHead = new ItemStack(Material.PLAYER_HEAD, 1);
        SkullMeta skullMeta = (SkullMeta) playerHead.getItemMeta();
        if (skullMeta != null) {
            skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(deceased.getUniqueId()));
            playerHead.setItemMeta(skullMeta);
        }
        ItemMeta meta = playerHead.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§rDeath Stash of " + deceased.getName());
            meta.setLore(Arrays.asList(
                    "§r⚔️ Killed by §e" + killerName + " 😈👿",
                    "§r§b📦 5 opens left 🤔😎",
                    "§r",
                    "§6⏳ Expires on: " + getExpiryDateGMT() + " GMT"
            ));

            NamespacedKey key = new NamespacedKey(this, "deathstash");
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, uniqueId);

            playerHead.setItemMeta(meta);
        }

        event.getDrops().clear();
        deceased.getWorld().dropItemNaturally(deceased.getLocation(), playerHead);

        savePlayerInventory(deceased, uniqueId);
        deathsConfig = YamlConfiguration.loadConfiguration(deathsFile);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        getLogger().info("PlayerInteractEvent triggered by " + event.getPlayer().getName());
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.PLAYER_HEAD) {
            getLogger().info("No valid item or not a player head in hand for " + event.getPlayer().getName());
            return;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            getLogger().info("No item meta found for " + event.getPlayer().getName());
            return;
        }

        NamespacedKey key = new NamespacedKey(this, "deathstash");
        String uniqueId = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (uniqueId == null) {
            getLogger().info("No uniqueId found in item meta for " + event.getPlayer().getName());
            return;
        }

        if (deathsConfig.contains("deaths." + uniqueId)) {
            if (deathsConfig.getBoolean("deaths." + uniqueId + ".expired", false)) {
                player.sendMessage("§cThis stash is expired and can no longer be accessed.");
                return;
            }
            getLogger().info("Death stash found for unique ID: " + uniqueId + " for player " + event.getPlayer().getName());
            Inventory deathInventory = loadDeathInventory(uniqueId);
            if (deathInventory != null) {
                event.getPlayer().openInventory(deathInventory);
                getLogger().info("Opened death stash for " + event.getPlayer().getName() + " with unique ID: " + uniqueId);
            }
        }
    }

    private String getExpiryDateGMT() {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        calendar.add(Calendar.DAY_OF_YEAR, 1);
        return String.format("%tF %tT GMT", calendar, calendar);
    }

    private void savePlayerInventory(Player player, String uniqueId) {
        String path = "deaths." + uniqueId;
        deathsConfig.set(path + ".player", player.getName());
        deathsConfig.set(path + ".time", System.currentTimeMillis() + 24 * 60 * 60 * 1000);

        List<ItemStack> items = new ArrayList<>();
        items.addAll(List.of(player.getInventory().getContents()));

        deathsConfig.set(path + ".items", items.stream().filter(Objects::nonNull).collect(Collectors.toList()));
        saveDeathsConfig();
    }

    private Inventory loadDeathInventory(String uniqueId) {
        getLogger().info("Loading death stash for unique ID: " + uniqueId);
        String path = "deaths." + uniqueId;
        List<ItemStack> items = (List<ItemStack>) deathsConfig.getList(path + ".items");
        if (items == null) {
            getLogger().info("No items found for unique ID: " + uniqueId);
            return null;
        }

        Inventory inventory = Bukkit.createInventory(null, 45, "Death Stash");
        getLogger().info("Created inventory for unique ID: " + uniqueId);
        for (int i = 0; i < items.size() && i < 36; i++) {
            inventory.setItem(i, items.get(i));
            getLogger().info("Set item in inventory slot " + i + " for unique ID: " + uniqueId);
        }
        return inventory;
    }

    private void loadAllDeathStashes() {
        if (deathsConfig.getConfigurationSection("deaths") != null) {
            Set<String> loadKeys = Optional.ofNullable(deathsConfig.getConfigurationSection("deaths")).map(section -> section.getKeys(false)).orElse(Collections.emptySet());
            for (String loadKey : loadKeys) {
                getLogger().info("Loading death stash for unique ID: " + loadKey);
                Inventory deathInventory = loadDeathInventory(loadKey);
                if (deathInventory != null) {
                    getLogger().info("Loaded death stash successfully for unique ID: " + loadKey);
                }
            }
        }
    }

    private void cleanupExpiredStashes() {
        long currentTime = System.currentTimeMillis();
        if (deathsConfig.getConfigurationSection("deaths") != null) {
            Set<String> cleanupKeys = deathsConfig.getConfigurationSection("deaths").getKeys(false);
            for (String cleanupKey : cleanupKeys) {
                long expiryTime = deathsConfig.getLong("deaths." + cleanupKey + ".time");
                if (currentTime > expiryTime) {
                    deathsConfig.set("deaths." + cleanupKey + ".expired", true);
                    deathsConfig.set("deaths." + cleanupKey + ".items", null);
                    deathsConfig.set("deaths." + cleanupKey + ".time", null);
                }
            }
            saveDeathsConfig();
        }
    }

    private void saveDeathsConfig() {
        try {
            deathsConfig.save(deathsFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}