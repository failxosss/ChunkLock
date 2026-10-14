package dev.chunklock.config;

import dev.chunklock.ChunkLockPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Zprávy z messages.yml (MiniMessage). */
public final class Messages {

    private final ChunkLockPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private YamlConfiguration cfg = new YamlConfiguration();

    public Messages(ChunkLockPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) plugin.saveResource("messages.yml", false);
        cfg = YamlConfiguration.loadConfiguration(file);
        InputStream in = plugin.getResource("messages.yml");
        if (in != null) {
            cfg.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        }
    }

    public static TagResolver ph(String key, Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }

    public String raw(String key) {
        return cfg.getString(key, "<red>Chybí zpráva: " + key);
    }

    private TagResolver all(TagResolver[] resolvers) {
        TagResolver[] arr = Arrays.copyOf(resolvers, resolvers.length + 1);
        arr[resolvers.length] = Placeholder.parsed("prefix", cfg.getString("prefix", ""));
        return TagResolver.resolver(arr);
    }

    public Component get(String key, TagResolver... resolvers) {
        return mm.deserialize(raw(key), all(resolvers));
    }

    public List<Component> list(String key, TagResolver... resolvers) {
        List<String> lines = cfg.getStringList(key);
        List<Component> out = new ArrayList<>();
        TagResolver r = all(resolvers);
        for (String line : lines) out.add(mm.deserialize(line, r));
        return out;
    }

    public void send(CommandSender to, String key, TagResolver... resolvers) {
        to.sendMessage(get(key, resolvers));
    }

    public void sendList(CommandSender to, String key, TagResolver... resolvers) {
        for (Component c : list(key, resolvers)) to.sendMessage(c);
    }

    public void actionBar(Player to, String key, TagResolver... resolvers) {
        to.sendActionBar(get(key, resolvers));
    }
}
