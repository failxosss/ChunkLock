package dev.chunklock.command;

import dev.chunklock.ChunkLockPlugin;
import dev.chunklock.config.Messages;
import dev.chunklock.gui.PermissionGui;
import dev.chunklock.manager.InviteManager.Invite;
import dev.chunklock.manager.IslandManager;
import dev.chunklock.model.ChunkPos;
import dev.chunklock.model.Island;
import dev.chunklock.model.IslandPermission;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static dev.chunklock.config.Messages.ph;

/** /chunk a všechny podpříkazy. */
public final class ChunkCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("home", "info", "invite", "accept", "deny", "kick",
            "leave", "members", "permission", "reset", "help");
    private static final List<String> ADMIN_SUBS = List.of("reload", "tp", "unlock", "delete");

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
            msg().sendList(sender, "help");
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
            case "reset" -> reset(p, args);
            default -> msg().sendList(p, "help");
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
        islands().teleport(p, island, () -> msg().send(p, "teleported", ph("owner", island.ownerName())));
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
        msg().send(p, "reset.started");
        islands().resetIsland(island, p);
    }

    // ------------------------------------------------------------------ admin

    private void admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chunklock.admin")) {
            msg().send(sender, "no-permission");
            return;
        }
        if (args.length < 2) {
            msg().send(sender, "usage.admin");
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
                if (island != null) teleportWithMessage(p, island);
            }
            case "unlock" -> {
                Island island = adminIsland(sender, args);
                if (island == null) return;
                if (args.length < 4) {
                    msg().send(sender, "usage.admin");
                    return;
                }
                ChunkPos rel;
                try {
                    rel = new ChunkPos(Integer.parseInt(args[3]), Integer.parseInt(args.length > 4 ? args[4] : "0"));
                } catch (NumberFormatException ex) {
                    msg().send(sender, "usage.admin");
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
            default -> msg().send(sender, "usage.admin");
        }
    }

    private Island adminIsland(CommandSender sender, String[] args) {
        if (args.length < 3) {
            msg().send(sender, "usage.admin");
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
            if (args.length == 3) {
                for (Island i : islands().all()) out.add(i.ownerName());
                return filter(out, args[2]);
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
