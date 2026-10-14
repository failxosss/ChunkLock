package dev.chunklock.manager;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Napojení na Vault. Třída se načítá jen pokud je Vault přítomný. */
public final class EconomyHook {

    private final Economy economy;

    private EconomyHook(Economy economy) {
        this.economy = economy;
    }

    /** @return hook, nebo null pokud není žádná ekonomika zaregistrovaná */
    public static EconomyHook tryCreate() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return null;
        return new EconomyHook(rsp.getProvider());
    }

    public boolean has(Player player, double amount) {
        return economy.has(player, amount);
    }

    public boolean withdraw(Player player, double amount) {
        return economy.withdrawPlayer(player, amount).transactionSuccess();
    }

    public String format(double amount) {
        return economy.format(amount);
    }
}
