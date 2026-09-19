package dev.shareitem;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ShareItemPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Component PREFIX = Component.text("[ShareItem]  ").color(NamedTextColor.GOLD);

    // source UUID -> request (only one pending per source)
    private final Map<UUID, PendingRequest> bySource = new ConcurrentHashMap<>();
    // id -> request
    private final Map<UUID, PendingRequest> byId = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Objects.requireNonNull(getCommand("si")).setExecutor(this);
        Objects.requireNonNull(getCommand("si")).setTabCompleter(this);
        getLogger().info("ShareItem 26.2 enabled - Paper build 121 compatible");
    }

    @Override
    public void onDisable() {
        // cancel tasks
        for (PendingRequest r : byId.values()) {
            if (r.getCancelWindowTask() != null) r.getCancelWindowTask().cancel();
            if (r.getExpireTask() != null) r.getExpireTask().cancel();
        }
        byId.clear();
        bySource.clear();
    }

    // ---------- Config helpers ----------

    private String msg(String path, String def) {
        return getConfig().getString("messages." + path, def);
    }

    private int cfgInt(String path, int def) {
        return getConfig().getInt(path, def);
    }

    private boolean cfgBool(String path, boolean def) {
        return getConfig().getBoolean(path, def);
    }

    private Component deserialize(String raw) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        // support legacy & colors via MiniMessage legacy? Convert & to MiniMessage is complex; just use MiniMessage.
        // Replace legacy &a etc with MiniMessage for convenience? Keep simple: let MiniMessage handle tags, & will show literally.
        // We do a quick conversion for & colors to avoid confusion:
        // We'll leave as is, but also handle & via legacy conversion if contains '&'
        try {
            return mm.deserialize(raw);
        } catch (Exception e) {
            return Component.text(raw);
        }
    }

    private Component withPlaceholders(String template, Map<String, String> placeholders) {
        if (template == null) return Component.empty();
        String s = template;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            s = s.replace("{" + e.getKey() + "}", e.getValue());
        }
        return deserialize(s);
    }

    private String replaceRaw(String template, Map<String, String> placeholders) {
        if (template == null) return "";
        String s = template;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            s = s.replace("{" + e.getKey() + "}", e.getValue());
        }
        return s;
    }

    private String formatMaterial(Material m) {
        String[] parts = m.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            sb.append(Character.toUpperCase(parts[i].charAt(0)));
            if (parts[i].length() > 1) sb.append(parts[i].substring(1));
            if (i < parts.length - 1) sb.append(" ");
        }
        return sb.toString();
    }

    private int countItems(Player p, Material mat) {
        int total = 0;
        for (ItemStack stack : p.getInventory().getContents()) {
            if (stack != null && stack.getType() == mat) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private void removeItems(Player p, Material mat, int amount) {
        int remaining = amount;
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != mat) continue;
            int amt = stack.getAmount();
            if (amt <= remaining) {
                p.getInventory().clear(i);
                remaining -= amt;
            } else {
                stack.setAmount(amt - remaining);
                remaining = 0;
            }
            if (remaining <= 0) break;
        }
        // also handle storage? getContents already includes storage, armor, offhand.
        // Ensure update
        p.updateInventory();
    }

    private void giveOrDrop(Player target, Material mat, int amount) {
        int remaining = amount;
        boolean droppedAny = false;
        while (remaining > 0) {
            int toGive = Math.min(remaining, mat.getMaxStackSize());
            ItemStack stack = new ItemStack(mat, toGive);
            HashMap<Integer, ItemStack> leftover = target.getInventory().addItem(stack);
            if (!leftover.isEmpty()) {
                for (ItemStack drop : leftover.values()) {
                    target.getWorld().dropItemNaturally(target.getLocation(), drop);
                    droppedAny = true;
                }
            }
            remaining -= toGive;
        }
        if (droppedAny && cfgBool("settings.drop-if-full", true)) {
            String info = msg("dropped-info", "Inventory full, items dropped at your location.");
            if (!info.isEmpty()) {
                target.sendMessage(PREFIX.append(deserialize(info)));
            }
        }
    }

    // ---------- Command ----------

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!command.getName().equalsIgnoreCase("si")) return false;
        if (args.length == 0) {
            sender.sendMessage(PREFIX.append(deserialize(msg("error-usage", "Usage: /si share <player> <item> [count|*]"))));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            if (!sender.hasPermission("shareitem.admin") && !sender.isOp()) {
                sender.sendMessage(PREFIX.append(withPlaceholders(msg("error-no-permission", "You don't have permission to do that."), Map.of())));
                return true;
            }
            reloadConfig();
            String reloaded = msg("reloaded", "ShareItem config reloaded.");
            sender.sendMessage(PREFIX.append(deserialize(reloaded)));
            return true;
        }
        if (!sub.equals("share")) {
            sender.sendMessage(PREFIX.append(deserialize(msg("error-usage", "Usage: /si share <player> <item> [count|*]"))));
            return true;
        }
        // shift args: /si share <player> <item> [count]
        // args[0]=share, args[1]=player, args[2]=item, args[3]=count
        if (args.length < 3) {
            sender.sendMessage(PREFIX.append(deserialize(msg("error-usage", "Usage: /si share <player> <item> [count|*]"))));
            return true;
        }

        if (!(sender instanceof Player source)) {
            String notPlayer = msg("error-not-player", "Only players can use this command.");
            sender.sendMessage(PREFIX.append(deserialize(notPlayer)));
            return true;
        }

        if (!source.hasPermission("shareitem.use")) {
            sender.sendMessage(PREFIX.append(deserialize(msg("error-no-permission", "You don't have permission to do that."))));
            return true;
        }

        String playerName = args[1];
        String itemStr = args[2];
        String countStr = args.length >= 4 ? args[3] : null;

        // Block selectors
        if (playerName.startsWith("@") || playerName.contains("@a") || playerName.contains("@p") || playerName.contains("@e") || playerName.contains("@r") || playerName.contains("@s")) {
            sender.sendMessage(PREFIX.append(deserialize(msg("error-selector-not-allowed", "Selectors like @a are not allowed. Specify a single player."))));
            return true;
        }
        // Also block wildcard/count contains @
        if (playerName.equals("*") || playerName.contains("*") && playerName.length() == 1) {
            // "*" as player name is not valid, treat as selector attempt
            sender.sendMessage(PREFIX.append(deserialize(msg("error-selector-not-allowed", "Selectors like @a are not allowed. Specify a single player."))));
            return true;
        }

        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) target = Bukkit.getPlayer(playerName); // fuzzy
        if (target == null) {
            sender.sendMessage(PREFIX.append(withPlaceholders(msg("error-player-not-found", "Player {player} not found or not online."), Map.of("player", playerName))));
            return true;
        }

        if (source.getUniqueId().equals(target.getUniqueId())) {
            sender.sendMessage(PREFIX.append(deserialize(msg("error-self-share", "You cannot share items to yourself."))));
            return true;
        }

        // Material parse
        String normalizedItem = itemStr;
        if (normalizedItem.contains(":")) {
            normalizedItem = normalizedItem.substring(normalizedItem.indexOf(':') + 1);
        }
        Material mat = Material.matchMaterial(normalizedItem);
        if (mat == null) {
            // try with minecraft prefix handling
            mat = Material.matchMaterial(itemStr);
        }
        if (mat == null || mat.isAir() || !mat.isItem()) {
            sender.sendMessage(PREFIX.append(withPlaceholders(msg("error-invalid-item", "Invalid item: {item}"), Map.of("item", itemStr))));
            return true;
        }

        int amount;
        int have = countItems(source, mat);
        if (countStr == null || countStr.isEmpty()) {
            amount = 1;
        } else if (countStr.equals("*")) {
            amount = have;
            if (amount <= 0) {
                sender.sendMessage(PREFIX.append(withPlaceholders(msg("error-no-item", "You don't have any {item}."), Map.of("item", formatMaterial(mat)))));
                return true;
            }
        } else {
            try {
                amount = Integer.parseInt(countStr);
                if (amount <= 0) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                sender.sendMessage(PREFIX.append(withPlaceholders(msg("error-invalid-count", "Invalid count: {count} (use a positive number or * )"), Map.of("count", countStr))));
                return true;
            }
        }

        if (have < amount) {
            if (have == 0) {
                sender.sendMessage(PREFIX.append(withPlaceholders(msg("error-no-item", "You don't have any {item}."), Map.of("item", formatMaterial(mat)))));
            } else {
                sender.sendMessage(PREFIX.append(withPlaceholders(msg("error-not-enough", "You don't have enough {item} (need {need}, have {have})."),
                        Map.of("item", formatMaterial(mat), "need", String.valueOf(amount), "have", String.valueOf(have), "count", String.valueOf(amount)))));
            }
            return true;
        }

        // Check pending exists for source
        if (bySource.containsKey(source.getUniqueId())) {
            PendingRequest existing = bySource.get(source.getUniqueId());
            if (existing != null && !existing.isHandled() && !existing.isCancelled()) {
                sender.sendMessage(PREFIX.append(deserialize(msg("error-pending-exists", "You already have a pending share request. Wait or cancel it first."))));
                return true;
            } else {
                bySource.remove(source.getUniqueId());
            }
        }

        String sharedItem = amount + "x " + formatMaterial(mat);
        UUID reqId = UUID.randomUUID();
        PendingRequest req = new PendingRequest(reqId, source.getUniqueId(), source.getName(), target.getUniqueId(), target.getName(), mat, amount, sharedItem);
        bySource.put(source.getUniqueId(), req);
        byId.put(reqId, req);

        int cancelWindow = cfgInt("settings.cancel-window-seconds", 3);
        int requestTimeout = cfgInt("settings.request-timeout-seconds", 60);

        // Build source message with Cancel button (callback)
        Component cmdAccepted = withPlaceholders(msg("command-accepted", "Command Accepted."), Map.of());
        Component sharingLine = withPlaceholders(msg("sharing", "Sharing {sharedItem} to player {player}"),
                Map.of("sharedItem", sharedItem, "player", target.getName()));
        Component waitingLine = withPlaceholders(msg("waiting", "Waiting for target player to respond..."), Map.of());

        Component cancelButton = Component.text("[Cancel]")
                .color(NamedTextColor.RED)
                .clickEvent(ClickEvent.callback(audience -> {
                    if (audience instanceof Player p) handleCancel(p, reqId);
                }))
                .hoverEvent(HoverEvent.showText(Component.text("Click to cancel this request").color(NamedTextColor.GRAY)));

        Component sourceMsg = PREFIX.append(cmdAccepted)
                .append(Component.newline()).append(sharingLine)
                .append(Component.newline()).append(waitingLine)
                .append(Component.newline()).append(cancelButton);

        source.sendMessage(sourceMsg);

        // Schedule notify to target after cancel window
        final Player targetFinal = target;
        var task = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (req.isCancelled() || req.isHandled()) return;
            req.setNotified(true);

            // Build target message
            Component requestLine = withPlaceholders(msg("request-received", "{player} is requesting to send you {sharedItem}"),
                    Map.of("player", source.getName(), "sharedItem", sharedItem));

            Component claimButton = Component.text("[Claim]")
                    .color(NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.callback(audience -> {
                        if (audience instanceof Player p) handleClaim(p, reqId);
                    }))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to claim items").color(NamedTextColor.GRAY)));

            Component rejectButton = Component.text("[Reject]")
                    .color(NamedTextColor.RED)
                    .clickEvent(ClickEvent.callback(audience -> {
                        if (audience instanceof Player p) handleReject(p, reqId);
                    }))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to reject").color(NamedTextColor.GRAY)));

            Component spacer = Component.text("    ");

            Component targetMsg = PREFIX.append(requestLine)
                    .append(Component.newline()).append(claimButton).append(spacer).append(rejectButton);

            if (targetFinal.isOnline()) {
                targetFinal.sendMessage(targetMsg);
            }

            // Schedule expiry
            var expire = Bukkit.getScheduler().runTaskLater(this, () -> {
                if (req.isHandled() || req.isCancelled()) return;
                req.setHandled(true);
                bySource.remove(req.getSource());
                byId.remove(req.getId());
                Player src = Bukkit.getPlayer(req.getSource());
                Player tgt = Bukkit.getPlayer(req.getTarget());
                Component expired = deserialize(msg("expired", "Request expired or already handled."));
                if (src != null) src.sendMessage(PREFIX.append(expired));
                if (tgt != null) tgt.sendMessage(PREFIX.append(expired));
            }, requestTimeout * 20L);
            req.setExpireTask(expire);

        }, cancelWindow * 20L);
        req.setCancelWindowTask(task);

        return true;
    }

    private void handleCancel(Player p, UUID reqId) {
        PendingRequest req = byId.get(reqId);
        if (req == null || req.isHandled() || req.isCancelled()) {
            p.sendMessage(PREFIX.append(deserialize(msg("expired", "Request expired or already handled."))));
            return;
        }
        if (!req.getSource().equals(p.getUniqueId())) {
            // Only source can cancel; if target tries, ignore
            p.sendMessage(PREFIX.append(deserialize(msg("error-no-pending", "No pending request to handle."))));
            return;
        }
        req.setCancelled(true);
        if (req.getCancelWindowTask() != null) req.getCancelWindowTask().cancel();
        if (req.getExpireTask() != null) req.getExpireTask().cancel();
        req.setHandled(true);
        bySource.remove(req.getSource());
        byId.remove(req.getId());

        p.sendMessage(PREFIX.append(deserialize(msg("cancelled-source", "Request cancelled."))));

        // Notify target if already notified
        if (req.isNotified()) {
            Player tgt = Bukkit.getPlayer(req.getTarget());
            if (tgt != null) {
                tgt.sendMessage(PREFIX.append(deserialize(msg("cancelled-target", "The share request was cancelled by the sender."))));
            }
        }
    }

    private void handleClaim(Player targetPlayer, UUID reqId) {
        PendingRequest req = byId.get(reqId);
        if (req == null || req.isHandled() || req.isCancelled()) {
            targetPlayer.sendMessage(PREFIX.append(deserialize(msg("expired", "Request expired or already handled."))));
            return;
        }
        if (!req.getTarget().equals(targetPlayer.getUniqueId())) {
            targetPlayer.sendMessage(PREFIX.append(deserialize(msg("expired", "Request expired or already handled."))));
            return;
        }
        Player sourcePlayer = Bukkit.getPlayer(req.getSource());
        if (sourcePlayer == null || !sourcePlayer.isOnline()) {
            targetPlayer.sendMessage(PREFIX.append(withPlaceholders(msg("error-player-not-found", "Player {player} not found or not online."), Map.of("player", req.getSourceName()))));
            // cleanup
            req.setHandled(true);
            if (req.getCancelWindowTask() != null) req.getCancelWindowTask().cancel();
            if (req.getExpireTask() != null) req.getExpireTask().cancel();
            bySource.remove(req.getSource());
            byId.remove(req.getId());
            return;
        }

        // Re-validate amount at claim time
        int haveNow = countItems(sourcePlayer, req.getMaterial());
        if (haveNow < req.getAmount()) {
            // Notify both
            String notEnoughTpl = msg("error-not-enough", "You don't have enough {item} (need {need}, have {have}).");
            Map<String, String> ph = Map.of("item", formatMaterial(req.getMaterial()), "need", String.valueOf(req.getAmount()), "have", String.valueOf(haveNow), "count", String.valueOf(req.getAmount()));
            String raw = replaceRaw(notEnoughTpl, ph);
            Component err = deserialize(raw);
            sourcePlayer.sendMessage(PREFIX.append(err));
            targetPlayer.sendMessage(PREFIX.append(Component.text("Sender no longer has enough items.").color(NamedTextColor.RED)));
            // cleanup without transfer
            req.setHandled(true);
            if (req.getCancelWindowTask() != null) req.getCancelWindowTask().cancel();
            if (req.getExpireTask() != null) req.getExpireTask().cancel();
            bySource.remove(req.getSource());
            byId.remove(req.getId());
            return;
        }

        // Transfer
        removeItems(sourcePlayer, req.getMaterial(), req.getAmount());
        giveOrDrop(targetPlayer, req.getMaterial(), req.getAmount());

        req.setHandled(true);
        if (req.getCancelWindowTask() != null) req.getCancelWindowTask().cancel();
        if (req.getExpireTask() != null) req.getExpireTask().cancel();
        bySource.remove(req.getSource());
        byId.remove(req.getId());

        // Messages as per spec
        // source: "<playerName> had claimed the sent items"
        // target: "You have claimed the sent items"
        Component claimedSource = withPlaceholders(msg("claimed-source", "{player} had claimed the sent items"), Map.of("player", targetPlayer.getName()));
        Component claimedTarget = withPlaceholders(msg("claimed-target", "You have claimed the sent items"), Map.of("player", targetPlayer.getName()));

        sourcePlayer.sendMessage(PREFIX.append(claimedSource));
        targetPlayer.sendMessage(PREFIX.append(claimedTarget));
    }

    private void handleReject(Player targetPlayer, UUID reqId) {
        PendingRequest req = byId.get(reqId);
        if (req == null || req.isHandled() || req.isCancelled()) {
            targetPlayer.sendMessage(PREFIX.append(deserialize(msg("expired", "Request expired or already handled."))));
            return;
        }
        if (!req.getTarget().equals(targetPlayer.getUniqueId())) {
            targetPlayer.sendMessage(PREFIX.append(deserialize(msg("expired", "Request expired or already handled."))));
            return;
        }
        req.setHandled(true);
        if (req.getCancelWindowTask() != null) req.getCancelWindowTask().cancel();
        if (req.getExpireTask() != null) req.getExpireTask().cancel();
        bySource.remove(req.getSource());
        byId.remove(req.getId());

        Player sourcePlayer = Bukkit.getPlayer(req.getSource());
        Component rejectedSource = withPlaceholders(msg("rejected-source", "{player} had rejected the sent items"), Map.of("player", targetPlayer.getName()));
        Component rejectedTarget = withPlaceholders(msg("rejected-target", "You have rejected the sent items"), Map.of("player", targetPlayer.getName()));

        if (sourcePlayer != null) sourcePlayer.sendMessage(PREFIX.append(rejectedSource));
        targetPlayer.sendMessage(PREFIX.append(rejectedTarget));
    }

    // ---------- Tab completer ----------

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        if (!command.getName().equalsIgnoreCase("si")) return List.of();
        if (args.length == 1) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            if ("share".startsWith(sub)) out.add("share");
            if ("reload".startsWith(sub) && sender.hasPermission("shareitem.admin")) out.add("reload");
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("share")) {
            List<String> out = new ArrayList<>();
            String partial = args[1].toLowerCase();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(partial)) out.add(p.getName());
            }
            return out;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("share")) {
            String partial = args[2].toLowerCase();
            return Arrays.stream(Material.values())
                    .filter(m -> !m.isAir() && m.isItem())
                    .map(m -> m.name().toLowerCase())
                    .filter(n -> n.startsWith(partial))
                    .limit(50)
                    .collect(Collectors.toList());
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("share")) {
            String partial = args[3].toLowerCase();
            List<String> s = new ArrayList<>();
            if ("*".startsWith(partial)) s.add("*");
            if ("1".startsWith(partial)) s.add("1");
            if ("10".startsWith(partial)) s.add("10");
            if ("32".startsWith(partial)) s.add("32");
            if ("64".startsWith(partial)) s.add("64");
            return s;
        }
        return List.of();
    }
}
