package ch.laxacube.sudimetools;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Placeholder %sudime_hearts% : un cœur par tranche de 2 PV.
 * Le nombre n'est pas plafonné à 10, donc les joueurs avec plus de 20 PV
 * affichent automatiquement plus de cœurs.
 */
public final class HeartsExpansion extends PlaceholderExpansion {

    private final SudimeTools plugin;

    public HeartsExpansion(SudimeTools plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "sudime";
    }

    @Override
    public String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (!"hearts".equalsIgnoreCase(params) || player == null || !player.isOnline()) {
            return params == null || !"hearts".equalsIgnoreCase(params) ? null : "";
        }
        Player online = player.getPlayer();
        if (online == null) {
            return "";
        }
        int hearts = (int) Math.ceil(online.getHealth() / 2.0D);
        return hearts <= 0 ? "" : "\u2764".repeat(hearts);
    }
}
