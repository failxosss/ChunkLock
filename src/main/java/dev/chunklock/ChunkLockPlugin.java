package dev.chunklock;

import dev.chunklock.command.ChunkCommand;
import dev.chunklock.config.Messages;
import dev.chunklock.config.Settings;
import dev.chunklock.listener.CommandListener;
import dev.chunklock.listener.GuiListener;
import dev.chunklock.listener.MobListener;
import dev.chunklock.listener.MovementListener;
import dev.chunklock.listener.PanelListener;
import dev.chunklock.listener.PlayerListener;
import dev.chunklock.listener.ProtectionListener;
import dev.chunklock.manager.EconomyHook;
import dev.chunklock.manager.InviteManager;
import dev.chunklock.manager.IslandManager;
import dev.chunklock.manager.PanelManager;
import dev.chunklock.manager.ResourceScanner;
import dev.chunklock.manager.Storage;
import dev.chunklock.manager.UnlockService;
import dev.chunklock.model.Island;
import dev.chunklock.util.VoidGenerator;
import org.bukkit.command.PluginCommand;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

public final class ChunkLockPlugin extends JavaPlugin {

    private Settings settings;
    private Messages messages;
    private Storage storage;
    private IslandManager islands;
    private PanelManager panels;
    private UnlockService unlocks;
    private ResourceScanner resources;
    private InviteManager invites;
    private EconomyHook economy;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = new Settings(getConfig(), getLogger());
        messages = new Messages(this);
        storage = new Storage(this);
        invites = new InviteManager();
        islands = new IslandManager(this);
        panels = new PanelManager(this);
        unlocks = new UnlockService(this);
        resources = new ResourceScanner(this);

        if (!islands.init()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(new ProtectionListener(this), this);
        getServer().getPluginManager().registerEvents(new MovementListener(this), this);
        getServer().getPluginManager().registerEvents(new MobListener(this), this);
        getServer().getPluginManager().registerEvents(new PanelListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new CommandListener(this), this);
        getServer().getPluginManager().registerEvents(new GuiListener(), this);

        PluginCommand cmd = getCommand("chunk");
        if (cmd != null) {
            ChunkCommand handler = new ChunkCommand(this);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        // cedulky u zamčených chunků
        getServer().getScheduler().runTask(this, panels::sweepAll); // smaže případné zbytky po minulém běhu
        getServer().getScheduler().runTaskTimer(this, panels::tick, 40L, 40L);
        // pravidelné ukládání
        getServer().getScheduler().runTaskTimer(this, islands::saveAll, 6000L, 6000L);

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new dev.chunklock.hook.ChunkLockExpansion(this).register();
            getLogger().info("PlaceholderAPI expanze 'chunklock' zaregistrována.");
        }

        getLogger().info("ChunkLock zapnut, svět: " + settings.worldName);
    }

    @Override
    public void onDisable() {
        if (panels != null) panels.removeAll();
        if (islands != null) islands.saveAll();
    }

    /** Umožní použít plugin jako generátor ve světě (bukkit.yml / Multiverse). */
    @Override
    public @Nullable ChunkGenerator getDefaultWorldGenerator(String worldName, @Nullable String id) {
        return new VoidGenerator();
    }

    public void reloadAll() {
        reloadConfig();
        settings = new Settings(getConfig(), getLogger());
        messages.reload();
        if (islands != null) {
            // požadavky se počítají znovu podle nové konfigurace (odevzdané itemy zůstávají započtené)
            for (Island island : islands.all()) island.clearAllRequired();
        }
        if (panels != null) panels.removeAll(); // cedulky se za chvíli samy vytvoří znovu s novými čísly
    }

    /** Vault ekonomika se hledá líně, protože se poskytovatel může registrovat později než tenhle plugin. */
    public EconomyHook economy() {
        if (economy == null && getServer().getPluginManager().isPluginEnabled("Vault")) {
            economy = EconomyHook.tryCreate();
        }
        return economy;
    }

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public Storage storage() {
        return storage;
    }

    public IslandManager islands() {
        return islands;
    }

    public PanelManager panels() {
        return panels;
    }

    public UnlockService unlocks() {
        return unlocks;
    }

    public ResourceScanner resources() {
        return resources;
    }

    public InviteManager invites() {
        return invites;
    }
}
