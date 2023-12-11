package org.s7mple.regexfilter;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;

public class RegexFilter extends JavaPlugin implements Listener {

    private File configFile;
    private FileConfiguration config;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdir();
        }

        configFile = new File(getDataFolder(), "filter.yml");
        if (!configFile.exists()) {
            generateDefaultConfig();
        }
        config = YamlConfiguration.loadConfiguration(configFile);

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("regexfilter").setExecutor(this);

        Bukkit.getScheduler().runTaskTimer(this, this::checkPlayerInventories, 0L, 0L);
    }

    private void generateDefaultConfig() {
        FileConfiguration defaultConfig = new YamlConfiguration();
        defaultConfig.set("AntiUnicode.Regex", "[^\\x00-\\x7F]");
        defaultConfig.set("AntiUnicode.Enabled", true);
        defaultConfig.set("AntiUnicode.WarnAction", true);
        defaultConfig.set("AntiUnicode.Action", Collections.singletonList("tellraw %s {\"text\":\"Unicode characters are not allowed!\",\"color\":\"red\"}"));

        try {
            defaultConfig.save(configFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("regexfilter") && args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!(sender instanceof Player) || sender.hasPermission("regexfilter.reload")) {
                reloadConfig();
                config = YamlConfiguration.loadConfiguration(configFile);
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&8[&3&lR&b&lF&8] &7filter.yml &areloaded&7!"));
                return true;
            } else {
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&8[&3&lR&b&lF&8] &cYou don't have permission to use this command!"));
            }
        }
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&8[&3&lR&b&lF&8] &7Command: /regexfilter reload"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("regexfilter") && args.length == 1 && sender.hasPermission("regexfilter.reload")) {
            return Collections.singletonList("reload");
        }
        return Collections.emptyList();
    }

    private void handleAction(Player player, String triggeredKey, ItemStack itemStack) {
        List<String> actionList = config.getStringList(triggeredKey + ".Action");

        actionList.forEach(action -> {
            String formattedAction = String.format(action, player.getName());
            Bukkit.getScheduler().runTask(this, () -> Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), formattedAction));
        });
    }

    private void checkPlayerInventories() {
        Bukkit.getOnlinePlayers().forEach(player -> Bukkit.getScheduler().runTask(this, () -> player.getInventory().forEach(itemStack -> {
            if (itemStack != null) {
                handlePlayerInventoryItem(itemStack, player);
            }
        })));
    }

    private void handlePlayerInventoryItem(ItemStack itemStack, Player player) {
        if (itemStack.getType() == Material.WRITABLE_BOOK || itemStack.getType() == Material.WRITTEN_BOOK) {
            BookMeta bookMeta = (BookMeta) itemStack.getItemMeta();
            if (bookMeta != null && bookMeta.hasPages()) {
                if (player.hasPermission("regexfilter.book")) {
                    return;
                }

                List<String> pages = new ArrayList<>(bookMeta.getPages());
                boolean modified = false;

                for (int i = 0; i < pages.size(); i++) {
                    String pageContent = pages.get(i);
                    for (String triggeredKey : config.getKeys(true)) {
                        if (config.getBoolean(triggeredKey + ".Enabled") && isMatchingRegex(pageContent, config.getString(triggeredKey + ".Regex"))) {
                            pages.set(i, pageContent.replaceAll(config.getString(triggeredKey + ".Regex"), ""));
                            modified = true;
                            handleAction(player, triggeredKey, itemStack);
                            break;
                        }
                    }
                }

                if (modified) {
                    bookMeta.setPages(pages);
                    itemStack.setItemMeta(bookMeta);
                    player.updateInventory();
                }
            }
        } else {
            String itemName = itemStack.getType().toString();
            String itemDisplayName = itemStack.getItemMeta() != null && itemStack.getItemMeta().hasDisplayName()
                    ? itemStack.getItemMeta().getDisplayName()
                    : "";

            String fullItemName = itemName + " " + itemDisplayName;

            for (String triggeredKey : config.getKeys(true)) {
                if (config.getBoolean(triggeredKey + ".Enabled") && isMatchingRegex(fullItemName, config.getString(triggeredKey + ".Regex"))) {
                    Bukkit.getScheduler().runTask(this, () -> {
                        itemStack.setAmount(0);
                        player.updateInventory();
                    });
                    handleAction(player, triggeredKey, itemStack);
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        String[] lines = event.getLines();

        if (!player.hasPermission("regexfilter.sign")) {
            for (String line : lines) {
                config.getKeys(false).forEach(triggeredKey -> {
                    if (config.getBoolean(triggeredKey + ".Enabled") && isMatchingRegex(line, config.getString(triggeredKey + ".Regex"))) {
                        event.setCancelled(true);
                        handleAction(player, triggeredKey, null);
                    }
                });
            }
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        if (!player.hasPermission("regexfilter.chat")) {
            config.getKeys(false).forEach(triggeredKey -> {
                if (config.getBoolean(triggeredKey + ".Enabled") && isMatchingRegex(message, config.getString(triggeredKey + ".Regex"))) {
                    event.setCancelled(true);
                    handleAction(player, triggeredKey, null);
                }
            });
        }
    }

    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String command = event.getMessage().substring(1);

        if (!player.hasPermission("regexfilter.command")) {
            config.getKeys(false).forEach(triggeredKey -> {
                if (config.getBoolean(triggeredKey + ".Enabled") && isMatchingRegex(command, config.getString(triggeredKey + ".Regex"))) {
                    event.setCancelled(true);
                    handleAction(player, triggeredKey, null);
                }
            });
        }
    }

    private boolean isMatchingRegex(String input, String regex) {
        return Pattern.compile(regex).matcher(input).find();
    }
}
