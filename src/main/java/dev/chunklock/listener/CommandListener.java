package dev.chunklock.listener;

import dev.chunklock.ChunkLockPlugin;
import dev.chunklock.config.Settings;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;
import java.util.Set;

/** Omezení příkazů podle světa (world-commands v config.yml). */
public final class CommandListener implements Listener {

    private static final Set<String> CHUNK_LABELS = Set.of("chunk", "chunks");

    private final ChunkLockPlugin plugin;

    public CommandListener(ChunkLockPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (e.getPlayer().hasPermission("chunklock.bypass.commands")) return;

        Settings.CommandRule rule = plugin.settings().ruleFor(e.getPlayer().getWorld().getName());
        if (rule == null || rule.mode() == Settings.ListMode.NONE) return;

        String message = e.getMessage();
        if (message.length() < 2) return;
        String label = message.substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (CHUNK_LABELS.contains(label)) return;

        boolean listed = rule.commands().contains(label);
        boolean block = rule.mode() == Settings.ListMode.BLACKLIST ? listed : !listed;
        if (block) {
            e.setCancelled(true);
            plugin.messages().send(e.getPlayer(), "commands-blocked");
        }
    }
}
