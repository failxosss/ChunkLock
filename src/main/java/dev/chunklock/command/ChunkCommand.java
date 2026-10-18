package dev.chunklock.command;

import dev.chunklock.ChunkLockPlugin;
import dev.chunklock.config.Messages;
import dev.chunklock.config.Settings;
import dev.chunklock.gui.PermissionGui;
import dev.chunklock.manager.InviteManager.Invite;
import dev.chunklock.manager.IslandManager;
import dev.chunklock.manager.UnlockService;
import dev.chunklock.model.ChunkPos;
import dev.chunklock.model.Island;
import dev.chunklock.model.IslandPermission;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static dev.chunklock.config.Messages.ph;

/** /chunk a všechny podpříkazy. */
public final class ChunkCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("home", "info", "invite", "accept", "deny", "kick",
            "leave", "members", "permission", "mobs", "reset", "list", "switch", "invites", "map", "top", "contributions",
            "help");
    private static final List<String> ADMIN_SUBS = List.of("reload", "tp", "unlock", "lock", "delete", "reset",
            "list", "info", "addmember", "removemember", "mobs", "refresh", "needs", "backups", "restore", "give");

    private final ChunkLockPlugin plugin;

    public ChunkCommand(ChunkLockPlugin plugin) {
        this.plugin = plugin;
    }

    private Messages msg() {
        return plugin.messages();
    }

    private IslandManager islands() {
        return plugin.islands();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "go" : args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("admin")) {
            admin(sender, args);
            return true;
        }
        if (sub.equals("help")) {
            sendHelp(sender);
            return true;
        }
        if (!(sender instanceof Player p)) {
            msg().send(sender, "only-players");
            return true;
        }

        switch (sub) {
            case "go", "tp" -> go(p);
            case "home" -> home(p);
            case "info" -> info(p);
            case "invite" -> invite(p, args);
            case "accept" -> accept(p, args);
            case "deny" -> deny(p, args);
            case "kick" -> kick(p, args);
            case "leave" -> leave(p);
            case "members" -> members(p);
            case "permission", "permissions", "perm" -> permission(p, args);
            case "mobs" -> mobs(p, args);
            case "reset" -> reset(p, args);
            case "list" -> list(p);
            case "switch" -> switchIsland(p, args);
            case "invites" -> invites(p);
            case "map" -> map(p);
            case "top" -> top(p);
            case "contributions", "contrib", "team" -> contributions(p);
            default -> sendHelp(p);
        }
        return true;
    }

    // ------------------------------------------------------------------ teleporty

    private boolean worldAllowed(Player p) {
        if (plugin.settings().chunkCommandAllowed(p.getWorld().getName())) return true;
        msg().send(p, "world-disabled");
        return false;
    }

    private void go(Player p) {
        if (!worldAllowed(p)) return;
        Island island = islands().activeIsland(p.getUniqueId());
        if (island == null) {
            island = islands().createIsland(p);
            msg().send(p, "created");
        }
        teleportWithMessage(p, island);
    }

    private void home(Player p) {
        if (!worldAllowed(p)) return;
        Island own = islands().byOwner(p.getUniqueId());
        if (own == null) {
            own = islands().createIsland(p);
            msg().send(p, "created");
        }
        islands().setActive(p.getUniqueId(), p.getUniqueId());
        teleportWithMessage(p, own);
    }

    private void teleportWithMessage(Player p, Island island) {
        plugin.islandsFor(island).teleport(p, island, () -> msg().send(p, "teleported", ph("owner", island.ownerName())));
    }

    // ------------------------------------------------------------------ info / členové

    private void info(Player p) {
        Island island = islands().activeIsland(p.getUniqueId());
        if (island == null) {
            msg().send(p, "no-active-island");
            return;
        }
        msg().sendList(p, "info",
                ph("owner", island.ownerName()),
                ph("unlocked", island.unlockedCount()),
                ph("max", islands().maxChunks()),
                ph("members", island.members().size() + 1));
    }

    private void members(Player p) {
        Island island = islands().activeIsland(p.getUniqueId());
        if (island == null) {
            msg().send(p, "no-active-island");
            return;
        }
        msg().send(p, "members.header");
        msg().send(p, "members.owner", ph("owner", island.ownerName()));
        for (Island.Member m : island.members().values()) {
            List<String> perms = new ArrayList<>();
            for (IslandPermission perm : m.permissions()) perms.add(perm.id());
            msg().send(p, "members.entry", ph("player", m.name()), ph("perms", String.join(",", perms)));
        }
    }

    // ------------------------------------------------------------------ pozvánky

    private void invite(Player p, String[] args) {
        Island island = islands().activeIsland(p.getUniqueId());
        if (island == null) {
            msg().send(p, "no-active-island");
            return;
        }
        if (!island.hasPermission(p.getUniqueId(), IslandPermission.INVITE)) {
            msg().send(p, "no-invite-permission");
            return;
        }
        if (args.length < 2) {
            msg().send(p, "usage.invite");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            msg().send(p, "player-not-found");
            return;
        }
        if (target.equals(p)) {
            msg().send(p, "cannot-self");
            return;
        }
        if (island.isMember(target.getUniqueId())) {
            msg().send(p, "already-member");
            return;
        }
        if (island.members().size() >= plugin.settings().maxMembers) {
            msg().send(p, "island-full");
            return;
        }

        int seconds = plugin.settings().inviteExpireSeconds;
        Invite invite = new Invite(island.owner(), island.ownerName(), p.getName(),
                System.currentTimeMillis() + seconds * 1000L);
        if (!plugin.invites().add(target.getUniqueId(), invite)) {
            msg().send(p, "invite.already-invited");
            return;
        }

        Component accept = msg().get("invite.accept-button")
                .clickEvent(ClickEvent.runCommand("/chunk accept " + island.ownerName()));
        Component deny = msg().get("invite.deny-button")
                .clickEvent(ClickEvent.runCommand("/chunk deny " + island.ownerName()));
        msg().send(p, "invite.sent", ph("player", target.getName()));
        msg().send(target, "invite.received",
                ph("inviter", p.getName()),
                ph("seconds", seconds),
                Placeholder.component("accept", accept),
                Placeholder.component("deny", deny));
    }

    private Invite pickInvite(Player p, String[] args) {
        List<Invite> list = plugin.invites().valid(p.getUniqueId());
        if (list.isEmpty()) {
            msg().send(p, "invite.none");
            return null;
        }
        if (args.length >= 2) {
            for (Invite i : list) {
                if (i.ownerName().equalsIgnoreCase(args[1]) || i.inviterName().equalsIgnoreCase(args[1])) return i;
            }
            msg().send(p, "invite.none");
            return null;
        }
        if (list.size() == 1) return list.get(0);
        List<String> names = new ArrayList<>();
        for (Invite i : list) names.add(i.ownerName());
        msg().send(p, "invite.multiple", ph("list", String.join(", ", names)));
        return null;
    }

    private void accept(Player p, String[] args) {
        Invite invite = pickInvite(p, args);
        if (invite == null) return;

        Island island = islands().byOwner(invite.islandOwner());
        plugin.invites().remove(p.getUniqueId(), invite);
        if (island == null) {
            msg().send(p, "invite.invalid");
            return;
        }
        if (island.isMember(p.getUniqueId())) {
            msg().send(p, "already-member");
            return;
        }
        if (island.members().size() >= plugin.settings().maxMembers) {
            msg().send(p, "island-full");
            return;
        }

        island.addMember(p.getUniqueId(), p.getName(), plugin.settings().defaultPermissions);
        plugin.storage().saveIsland(island);
        islands().setActive(p.getUniqueId(), island.owner());

        msg().send(p, "invite.accepted", ph("owner", island.ownerName()));
        Player owner = Bukkit.getPlayer(island.owner());
        if (owner != null) msg().send(owner, "invite.joined-notify", ph("player", p.getName()));

        if (plugin.settings().teleportOnAccept && worldAllowed(p)) {
            teleportWithMessage(p, island);
        }
    }

    private void deny(Player p, String[] args) {
        Invite invite = pickInvite(p, args);
        if (invite == null) return;
        plugin.invites().remove(p.getUniqueId(), invite);
        msg().send(p, "invite.denied");
        Player owner = Bukkit.getPlayer(invite.islandOwner());
        if (owner != null) msg().send(owner, "invite.denied-notify", ph("player", p.getName()));
    }

    // ------------------------------------------------------------------ kick / leave

    private void kick(Player p, String[] args) {
        Island island = islands().byOwner(p.getUniqueId());
        if (island == null) {
            msg().send(p, "kick.only-owner");
            return;
        }
        if (args.length < 2) {
            msg().send(p, "usage.kick");
            return;
        }
        UUID target = island.findMember(args[1]);
        if (target == null) {
            msg().send(p, "kick.not-member");
            return;
        }
        String name = island.members().get(target).name();
        island.removeMember(target);
        plugin.storage().saveIsland(island);
        islands().onMemberRemoved(island, target);

        msg().send(p, "kick.success", ph("player", name));
        Player online = Bukkit.getPlayer(target);
        if (online != null) msg().send(online, "kick.kicked", ph("owner", island.ownerName()));
    }

    private void leave(Player p) {
        Island island = islands().activeIsland(p.getUniqueId());
        if (island == null) {
            msg().send(p, "no-active-island");
            return;
        }
        if (island.owner().equals(p.getUniqueId())) {
            msg().send(p, "leave.owner");
            return;
        }
        island.removeMember(p.getUniqueId());
        plugin.storage().saveIsland(island);
        islands().onMemberRemoved(island, p.getUniqueId());

        msg().send(p, "leave.success", ph("owner", island.ownerName()));
        Player owner = Bukkit.getPlayer(island.owner());
        if (owner != null) msg().send(owner, "leave.notify", ph("player", p.getName()));
    }

    // ------------------------------------------------------------------ oprávnění

    private void permission(Player p, String[] args) {
        Island island = islands().byOwner(p.getUniqueId());
        if (island == null) {
            msg().send(p, "permission.only-owner");
            return;
        }
        if (args.length < 2) {
            msg().send(p, "usage.permission");
            return;
        }
        UUID target = island.findMember(args[1]);
        if (target == null) {
            msg().send(p, "permission.not-member");
            return;
        }
        if (args.length == 2) {
            new PermissionGui(plugin, island, target).open(p);
            return;
        }

        IslandPermission perm = IslandPermission.parse(args[2]).orElse(null);
        if (perm == null) {
            List<String> all = new ArrayList<>();
            for (IslandPermission ip : IslandPermission.values()) all.add(ip.id());
            msg().send(p, "permission.unknown", ph("perms", String.join(", ", all)));
            return;
        }

        Island.Member member = island.members().get(target);
        boolean value;
        if (args.length >= 4) {
            value = Boolean.parseBoolean(args[3]) || args[3].equalsIgnoreCase("on") || args[3].equalsIgnoreCase("ano");
        } else {
            value = !member.permissions().contains(perm);
        }
        if (value) {
            member.permissions().add(perm);
        } else {
            member.permissions().remove(perm);
        }
        plugin.storage().saveIsland(island);
        msg().send(p, "permission.set", ph("perm", perm.id()), ph("player", member.name()),
                Placeholder.component("value", msg().get(value ? "permission.enabled" : "permission.disabled")));
    }

    // ------------------------------------------------------------------ reset

    private void reset(Player p, String[] args) {
        Island island = islands().byOwner(p.getUniqueId());
        if (island == null) {
            msg().send(p, "reset.no-island");
            return;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
            msg().send(p, "reset.confirm");
            return;
        }
        if (plugin.getConfig().getBoolean("reset.clear-inventory", true)) {
            p.getInventory().clear();
            p.setItemOnCursor(null);
        }
        msg().send(p, "reset.started");
        islands().resetIsland(island, p);
    }

    // ------------------------------------------------------------------ mobové

    /** /chunk mobs [on|off] - zapne/vypne spawnování mobů na vlastním chunku. */
    private void mobs(Player p, String[] args) {
        Island island = islands().byOwner(p.getUniqueId());
        if (island == null) {
            msg().send(p, "mobs.owner-only");
            return;
        }
        boolean value;
        if (args.length >= 2) {
            String a = args[1].toLowerCase(Locale.ROOT);
            if (a.equals("on") || a.equals("true")) value = true;
            else if (a.equals("off") || a.equals("false")) value = false;
            else {
                msg().send(p, "usage.mobs");
                return;
            }
        } else {
            value = !island.mobSpawning();
        }
        island.setMobSpawning(value);
        plugin.storage().saveIsland(island);
        if (!value) removeHostileMobs(island);
        msg().send(p, value ? "mobs.enabled" : "mobs.disabled");
    }

    /** Odstraní nepřátelské moby (bez jména) z načtených odemčených chunků ostrova. */
    private void removeHostileMobs(Island island) {
        World world = islands().world();
        if (world == null) return;
        int cx = islands().centerChunkX(island);
        int cz = islands().centerChunkZ(island);
        for (long key : island.unlockedKeys()) {
            ChunkPos rel = ChunkPos.fromKey(key);
            int x = cx + rel.x();
            int z = cz + rel.z();
            if (!world.isChunkLoaded(x, z)) continue;
            for (Entity e : world.getChunkAt(x, z).getEntities()) {
                if (e instanceof Enemy && e.customName() == null) e.remove();
            }
        }
    }

    private void sendHelp(CommandSender to) {
        msg().sendList(to, "help");
        msg().sendList(to, "help-more");
        if (to.hasPermission("chunklock.admin")) msg().sendList(to, "help-admin");
    }

    // ------------------------------------------------------------------ nové příkazy hráčů

    /** /chunk list - chunky, kde jsem majitel nebo člen. */
    private void list(Player p) {
        List<Island> mine = new ArrayList<>();
        for (Island i : islands().all()) {
            if (i.isMember(p.getUniqueId())) mine.add(i);
        }
        if (mine.isEmpty()) {
            msg().send(p, "no-active-island");
            return;
        }
        Island active = islands().activeIsland(p.getUniqueId());
        msg().send(p, "list.header");
        for (Island i : mine) {
            msg().send(p, i == active ? "list.entry-active" : "list.entry",
                    ph("owner", i.ownerName()), ph("unlocked", i.unlockedCount()),
                    ph("members", i.members().size() + 1));
        }
    }

    /** /chunk switch <majitel> - přepne chunk, na který tě teleportuje /chunk. */
    private void switchIsland(Player p, String[] args) {
        if (args.length < 2) {
            msg().send(p, "usage.switch");
            return;
        }
        Island island = islands().byOwnerName(args[1]);
        if (island == null || !island.isMember(p.getUniqueId())) {
            msg().send(p, "switch.not-member");
            return;
        }
        islands().setActive(p.getUniqueId(), island.owner());
        msg().send(p, "switch.success", ph("owner", island.ownerName()));
    }

    /** /chunk invites - čekající pozvánky. */
    private void invites(Player p) {
        List<Invite> list = plugin.invites().valid(p.getUniqueId());
        if (list.isEmpty()) {
            msg().send(p, "invite.none");
            return;
        }
        msg().send(p, "invites.header");
        for (Invite i : list) {
            long seconds = Math.max(0, (i.expiresAt() - System.currentTimeMillis()) / 1000);
            Component accept = msg().get("invite.accept-button")
                    .clickEvent(ClickEvent.runCommand("/chunk accept " + i.ownerName()));
            Component deny = msg().get("invite.deny-button")
                    .clickEvent(ClickEvent.runCommand("/chunk deny " + i.ownerName()));
            msg().send(p, "invites.entry", ph("owner", i.ownerName()), ph("inviter", i.inviterName()),
                    ph("seconds", seconds), Placeholder.component("accept", accept),
                    Placeholder.component("deny", deny));
        }
    }

    /** /chunk map - textová mapa odemčených chunků (sever nahoře). */
    private void map(Player p) {
        Island island = islands().activeIsland(p.getUniqueId());
        if (island == null) {
            msg().send(p, "no-active-island");
            return;
        }
        int r = Math.min(5, islands().radius());
        ChunkPos here = null;
        IslandManager.Plot plot = islands().plotAt(p.getLocation());
        if (plot != null && plot.island() == island) here = plot.rel();

        msg().send(p, "map.header", ph("owner", island.ownerName()),
                ph("unlocked", island.unlockedCount()), ph("max", islands().maxChunks()));
        for (int z = -r; z <= r; z++) {
            Component row = Component.empty();
            for (int x = -r; x <= r; x++) {
                ChunkPos c = new ChunkPos(x, z);
                Component cell;
                if (c.equals(here)) cell = Component.text("◉ ", NamedTextColor.YELLOW);
                else if (x == 0 && z == 0) cell = Component.text("◆ ", NamedTextColor.GOLD);
                else if (island.isUnlocked(c)) cell = Component.text("■ ", NamedTextColor.GREEN);
                else if (islands().canUnlock(island, c)) cell = Component.text("■ ", NamedTextColor.RED);
                else cell = Component.text("■ ", NamedTextColor.DARK_GRAY);
                row = row.append(cell);
            }
            p.sendMessage(row);
        }
        msg().sendList(p, "map.legend");
    }

    /** /chunk top - žebříček podle počtu odemčených chunků. */
    private void top(Player p) {
        List<Island> sorted = new ArrayList<>(islands().all());
        sorted.sort((a, b) -> Integer.compare(b.unlockedCount(), a.unlockedCount()));
        msg().send(p, "top.header");
        int shown = Math.min(10, sorted.size());
        for (int i = 0; i < shown; i++) {
            Island is = sorted.get(i);
            msg().send(p, "top.entry", ph("pos", i + 1), ph("player", is.ownerName()),
                    ph("unlocked", is.unlockedCount()));
        }
        Island own = islands().byOwner(p.getUniqueId());
        int rank = own == null ? -1 : sorted.indexOf(own);
        if (rank >= shown) {
            msg().send(p, "top.own", ph("pos", rank + 1), ph("unlocked", own.unlockedCount()));
        }
    }

    /** /chunk contributions - kolik itemů kdo odevzdal na aktivním chunku (týmové odemykání). */
    private void contributions(Player p) {
        Island island = islands().activeIsland(p.getUniqueId());
        if (island == null) {
            msg().send(p, "no-active-island");
            return;
        }
        List<Map.Entry<UUID, Integer>> sorted = new ArrayList<>(island.contributions().entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        if (sorted.isEmpty()) {
            msg().send(p, "contributions.none", ph("owner", island.ownerName()));
            return;
        }
        msg().send(p, "contributions.header", ph("owner", island.ownerName()));
        int shown = Math.min(10, sorted.size());
        for (int i = 0; i < shown; i++) {
            Map.Entry<UUID, Integer> e = sorted.get(i);
            msg().send(p, "contributions.entry", ph("pos", i + 1), ph("player", island.nameOf(e.getKey())),
                    ph("items", e.getValue()));
        }
    }

    // ------------------------------------------------------------------ admin

    private void admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chunklock.admin")) {
            msg().send(sender, "no-permission");
            return;
        }
        if (args.length < 2) {
            adminUsage(sender);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadAll();
                msg().send(sender, "admin.reloaded");
            }
            case "tp" -> {
                if (!(sender instanceof Player p)) {
                    msg().send(sender, "only-players");
                    return;
                }
                Island island = adminIsland(sender, args);
                if (island == null) return;
                if (args.length >= 4) { // /chunk admin tp <hráč> [overworld|nether|end]
                    IslandManager dm = plugin.islands(dev.chunklock.model.Dimension.parse(args[3]));
                    Island child = dm == null ? null : dm.byOwner(island.owner());
                    if (child == null) {
                        msg().send(sender, "admin.no-island", ph("player", args[2] + " (" + args[3] + ")"));
                        return;
                    }
                    island = child;
                }
                teleportWithMessage(p, island);
            }
            case "unlock" -> {
                Island island = adminIsland(sender, args);
                if (island == null) return;
                if (args.length < 4) {
                    adminUsage(sender);
                    return;
                }
                ChunkPos rel;
                try {
                    rel = new ChunkPos(Integer.parseInt(args[3]), Integer.parseInt(args.length > 4 ? args[4] : "0"));
                } catch (NumberFormatException ex) {
                    adminUsage(sender);
                    return;
                }
                if (!islands().canUnlock(island, rel)) {
                    msg().send(sender, "admin.unlock-invalid");
                    return;
                }
                islands().unlockChunk(island, rel);
                msg().send(sender, "admin.unlocked", ph("x", rel.x()), ph("z", rel.z()));
            }
            case "delete" -> {
                Island island = adminIsland(sender, args);
                if (island == null) return;
                String name = island.ownerName();
                islands().deleteIsland(island);
                msg().send(sender, "admin.deleted", ph("player", name));
            }
            case "lock" -> {
                Island island = adminIsland(sender, args);
                if (island == null) return;
                if (args.length < 5) {
                    adminUsage(sender);
                    return;
                }
                ChunkPos rel;
                try {
                    rel = new ChunkPos(Integer.parseInt(args[3]), Integer.parseInt(args[4]));
                } catch (NumberFormatException ex) {
                    adminUsage(sender);
                    return;
                }
                if (!islands().lockChunk(island, rel)) {
                    msg().send(sender, "admin.lock-invalid");
                    return;
                }
                msg().send(sender, "admin.locked", ph("x", rel.x()), ph("z", rel.z()));
            }
            case "reset" -> {
                Island island = adminIsland(sender, args);
                if (island == null) return;
                islands().resetIsland(island, Bukkit.getPlayer(island.owner()));
                msg().send(sender, "admin.reset-started", ph("player", island.ownerName()));
            }
            case "list" -> {
                List<Island> sorted = new ArrayList<>(islands().all());
                sorted.sort((a, b) -> Integer.compare(b.unlockedCount(), a.unlockedCount()));
                msg().send(sender, "admin.list-header", ph("count", sorted.size()));
                int shown = Math.min(30, sorted.size());
                for (int i = 0; i < shown; i++) {
                    Island is = sorted.get(i);
                    boolean online = Bukkit.getPlayer(is.owner()) != null;
                    msg().send(sender, online ? "admin.list-entry-online" : "admin.list-entry",
                            ph("owner", is.ownerName()), ph("unlocked", is.unlockedCount()),
                            ph("members", is.members().size() + 1));
                }
                if (sorted.size() > shown) {
                    msg().send(sender, "admin.list-more", ph("more", sorted.size() - shown));
                }
            }
            case "info" -> {
                Island island = adminIsland(sender, args);
                if (island == null) return;
                List<String> names = new ArrayList<>();
                for (Island.Member m : island.members().values()) names.add(m.name());
                msg().sendList(sender, "admin.info",
                        ph("owner", island.ownerName()),
                        ph("unlocked", island.unlockedCount()),
                        ph("max", islands().maxChunks()),
                        ph("members", names.isEmpty() ? "-" : String.join(", ", names)),
                        ph("mobs", island.mobSpawning() ? "ano" : "ne"),
                        ph("grid", island.gridX() + ", " + island.gridZ()));
            }
            case "addmember" -> {
                Island island = adminIsland(sender, args);
                if (island == null) return;
                if (args.length < 4) {
                    adminUsage(sender);
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[3]);
                if (target == null) {
                    msg().send(sender, "player-not-found");
                    return;
                }
                if (island.isMember(target.getUniqueId())) {
                    msg().send(sender, "already-member");
                    return;
                }
                if (island.members().size() >= plugin.settings().maxMembers) {
                    msg().send(sender, "island-full");
                    return;
                }
                island.addMember(target.getUniqueId(), target.getName(), plugin.settings().defaultPermissions);
                plugin.storage().saveIsland(island);
                msg().send(sender, "admin.member-added", ph("player", target.getName()),
                        ph("owner", island.ownerName()));
            }
            case "removemember" -> {
                Island island = adminIsland(sender, args);
                if (island == null) return;
                if (args.length < 4) {
                    adminUsage(sender);
                    return;
                }
                UUID target = island.findMember(args[3]);
                if (target == null) {
                    msg().send(sender, "admin.not-member");
                    return;
                }
                String name = island.members().get(target).name();
                island.removeMember(target);
                plugin.storage().saveIsland(island);
                islands().onMemberRemoved(island, target);
                msg().send(sender, "admin.member-removed", ph("player", name), ph("owner", island.ownerName()));
            }
            case "mobs" -> {
                Island island = adminIsland(sender, args);
                if (island == null) return;
                boolean value;
                if (args.length >= 4) {
                    String a = args[3].toLowerCase(Locale.ROOT);
                    if (a.equals("on") || a.equals("true")) value = true;
                    else if (a.equals("off") || a.equals("false")) value = false;
                    else {
                        adminUsage(sender);
                        return;
                    }
                } else {
                    value = !island.mobSpawning();
                }
                island.setMobSpawning(value);
                plugin.storage().saveIsland(island);
                if (!value) removeHostileMobs(island);
                msg().send(sender, "admin.mobs-set", ph("owner", island.ownerName()),
                        ph("value", value ? "zapnuto" : "vypnuto"));
            }
            case "needs" -> {
                Island island = adminIsland(sender, args);
                if (island == null) return;
                msg().send(sender, "admin.needs-header", ph("owner", island.ownerName()));
                for (ChunkPos locked : islands().lockedFrontier(island)) {
                    msg().send(sender, "admin.needs-chunk", ph("x", locked.x()), ph("z", locked.z()));
                    for (Settings.Requirement r : plugin.unlocks().requirements(island, locked)) {
                        msg().send(sender, "admin.needs-line",
                                ph("item", UnlockService.pretty(r.material())),
                                ph("need", r.amount()),
                                ph("have", plugin.unlocks().available(island, locked, r.material())));
                    }
                }
            }
            case "give" -> give(sender, args);
            case "backups" -> backups(sender, args.length >= 3 ? args[2] : null);
            case "restore" -> restore(sender, args);
            case "refresh" -> {
                Island island = adminIsland(sender, args);
                if (island == null) return;
                island.clearAllRequired(); // požadavky se spočítají znovu
                plugin.panels().spawn(island);
                msg().send(sender, "admin.refreshed", ph("owner", island.ownerName()));
            }
            default -> adminUsage(sender);
        }
    }

    /** /chunk admin give <hráč> [počet] - odemykací svitek na "počet" odemčení chunků zdarma. */
    private void give(CommandSender sender, String[] args) {
        if (args.length < 3) {
            msg().send(sender, "admin.give-usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            msg().send(sender, "player-not-found");
            return;
        }
        int count = 1;
        if (args.length >= 4) {
            try {
                count = Integer.parseInt(args[3]);
            } catch (NumberFormatException ex) {
                count = 0;
            }
        }
        if (count < 1 || count > 1000) {
            msg().send(sender, "admin.give-usage");
            return;
        }
        ItemStack token = plugin.unlocks().tokens().create(count);
        for (ItemStack rest : target.getInventory().addItem(token).values()) { // plný inventář: spadne na zem
            target.getWorld().dropItemNaturally(target.getLocation(), rest);
        }
        msg().send(sender, "admin.give-done", ph("player", target.getName()), ph("count", count));
        msg().send(target, "token.received", ph("count", count));
    }

    /** /chunk admin backups [hráč] - zálohy ostrovů (před resetem / smazáním). */
    private void backups(CommandSender sender, String filter) {
        List<File> files = plugin.storage().listBackups(filter);
        if (files.isEmpty()) {
            msg().send(sender, "admin.backups-none");
            return;
        }
        msg().send(sender, "admin.backups-header", ph("count", files.size()));
        int shown = Math.min(15, files.size());
        for (int i = 0; i < shown; i++) {
            String name = files.get(i).getName().replaceFirst("\\.yml$", "");
            Component button = msg().get("admin.backups-restore-button")
                    .clickEvent(ClickEvent.suggestCommand("/chunk admin restore " + name));
            msg().send(sender, "admin.backups-entry", ph("name", name), Placeholder.component("button", button));
        }
        if (files.size() > shown) {
            msg().send(sender, "admin.backups-more", ph("more", files.size() - shown));
        }
    }

    /** /chunk admin restore <záloha> - obnoví smazaný (nebo resetovaný a pak smazaný) ostrov. */
    private void restore(CommandSender sender, String[] args) {
        if (args.length < 3) {
            msg().send(sender, "admin.restore-usage");
            return;
        }
        File file = plugin.storage().findBackup(args[2]);
        if (file == null) {
            msg().send(sender, "admin.restore-not-found", ph("name", args[2]));
            return;
        }
        Island restored = plugin.storage().readBackup(file);
        if (restored == null) {
            msg().send(sender, "admin.restore-invalid");
            return;
        }
        IslandManager target = plugin.islands(restored.dimension());
        if (target == null) {
            msg().send(sender, "portal.disabled");
            return;
        }
        switch (target.restoreIsland(restored)) {
            case OK -> msg().send(sender, "admin.restore-started", ph("player", restored.ownerName()),
                    ph("chunks", restored.unlockedCount()));
            case HAS_ISLAND -> msg().send(sender, "admin.restore-has-island", ph("player", restored.ownerName()));
            case SLOT_TAKEN -> msg().send(sender, "admin.restore-slot-taken");
            case CLEARING -> msg().send(sender, "admin.restore-clearing");
            case NO_PARENT -> msg().send(sender, "admin.restore-no-parent", ph("player", restored.ownerName()));
        }
    }

    private void adminUsage(CommandSender sender) {
        msg().sendList(sender, "help-admin");
    }

    private Island adminIsland(CommandSender sender, String[] args) {
        if (args.length < 3) {
            adminUsage(sender);
            return null;
        }
        Island island = islands().byOwnerName(args[2]);
        if (island == null) msg().send(sender, "admin.no-island", ph("player", args[2]));
        return island;
    }

    // ------------------------------------------------------------------ tab complete

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(SUBS);
            if (sender.hasPermission("chunklock.admin")) out.add("admin");
            return filter(out, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("admin") && sender.hasPermission("chunklock.admin")) {
            if (args.length == 2) return filter(new ArrayList<>(ADMIN_SUBS), args[1]);
            String asub = args[1].toLowerCase(Locale.ROOT);
            if (args.length == 3 && asub.equals("give")) {
                for (Player online : Bukkit.getOnlinePlayers()) out.add(online.getName());
                return filter(out, args[2]);
            }
            if (args.length == 4 && asub.equals("give")) return filter(List.of("1", "5", "10", "25"), args[3]);
            if (args.length == 3 && asub.equals("restore")) {
                for (File f : plugin.storage().listBackups(null)) out.add(f.getName().replaceFirst("\\.yml$", ""));
                return filter(out, args[2]);
            }
            if (args.length == 3 && !asub.equals("list") && !asub.equals("reload")) {
                for (Island i : islands().all()) out.add(i.ownerName());
                return filter(out, args[2]);
            }
            if (args.length == 4 && asub.equals("tp")) return filter(List.of("overworld", "nether", "end"), args[3]);
            if (args.length == 4) {
                switch (asub) {
                    case "addmember" -> {
                        for (Player online : Bukkit.getOnlinePlayers()) out.add(online.getName());
                        return filter(out, args[3]);
                    }
                    case "removemember" -> {
                        Island is = islands().byOwnerName(args[2]);
                        if (is != null) {
                            for (Island.Member m : is.members().values()) out.add(m.name());
                        }
                        return filter(out, args[3]);
                    }
                    case "mobs" -> {
                        return filter(List.of("on", "off"), args[3]);
                    }
                    default -> {
                    }
                }
            }
            return out;
        }
        if (!(sender instanceof Player p)) return out;

        switch (sub) {
            case "invite" -> {
                if (args.length == 2) {
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        if (!online.equals(p)) out.add(online.getName());
                    }
                    return filter(out, args[1]);
                }
            }
            case "kick", "permission", "permissions", "perm" -> {
                Island own = islands().byOwner(p.getUniqueId());
                if (own == null) return out;
                if (args.length == 2) {
                    for (Map.Entry<UUID, Island.Member> e : own.members().entrySet()) out.add(e.getValue().name());
                    return filter(out, args[1]);
                }
                if (args.length == 3 && !sub.equals("kick")) {
                    for (IslandPermission ip : IslandPermission.values()) out.add(ip.id());
                    return filter(out, args[2]);
                }
                if (args.length == 4 && !sub.equals("kick")) return filter(List.of("true", "false"), args[3]);
            }
            case "accept", "deny" -> {
                if (args.length == 2) {
                    for (Invite i : plugin.invites().valid(p.getUniqueId())) out.add(i.ownerName());
                    return filter(out, args[1]);
                }
            }
            case "mobs" -> {
                if (args.length == 2) return filter(List.of("on", "off"), args[1]);
            }
            case "switch" -> {
                if (args.length == 2) {
                    for (Island i : islands().all()) {
                        if (i.isMember(p.getUniqueId())) out.add(i.ownerName());
                    }
                    return filter(out, args[1]);
                }
            }
            case "reset" -> {
                if (args.length == 2) return filter(List.of("confirm"), args[1]);
            }
            default -> {
            }
        }
        return out;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : options) {
            if (s.toLowerCase(Locale.ROOT).startsWith(p)) out.add(s);
        }
        return out;
    }
}
