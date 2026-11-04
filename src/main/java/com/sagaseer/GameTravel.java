package com.sagaseer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sagaseer.ChangeAwareSourceCapture.Observation;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarbitID;

/**
 * The state.travel row: travel unlocks and the current books, raw from the player's varbits; the Backend decides what they allow.
 * Uploaded only when it changes.
 */
final class GameTravel
{
    /**
     * One-time unlocks by varbit name (the website owns the words): newer prayers, quetzal sites, balloon routes, Slayer rewards.
     * True when not 0.
     */
    static final Map<String, Integer> UNLOCKS = unlocks(
        "PRAYER_DEADEYE_UNLOCKED", VarbitID.PRAYER_DEADEYE_UNLOCKED,
        "PRAYER_MYSTIC_VIGOUR_UNLOCKED", VarbitID.PRAYER_MYSTIC_VIGOUR_UNLOCKED,
        "QUETZAL_FORTIS", VarbitID.QUETZAL_FORTIS, "QUETZAL_TEOMAT", VarbitID.QUETZAL_TEOMAT,
        "QUETZAL_SUNSETCOAST", VarbitID.QUETZAL_SUNSETCOAST, "QUETZAL_HUNTERGUILD", VarbitID.QUETZAL_HUNTERGUILD,
        "QUETZAL_CAMTORUM", VarbitID.QUETZAL_CAMTORUM, "QUETZAL_COLOSSALWYRM", VarbitID.QUETZAL_COLOSSALWYRM,
        "QUETZAL_OUTERFORTIS", VarbitID.QUETZAL_OUTERFORTIS, "QUETZAL_COLOSSEUM", VarbitID.QUETZAL_COLOSSEUM,
        "QUETZAL_ALDARIN", VarbitID.QUETZAL_ALDARIN, "QUETZAL_QUETZACALLIGORGE", VarbitID.QUETZAL_QUETZACALLIGORGE,
        "QUETZAL_SALVAGEROVERLOOK", VarbitID.QUETZAL_SALVAGEROVERLOOK, "QUETZAL_TALTEKLAN", VarbitID.QUETZAL_TALTEKLAN,
        "QUETZAL_AUBURNVALLEY", VarbitID.QUETZAL_AUBURNVALLEY, "QUETZAL_KASTORI", VarbitID.QUETZAL_KASTORI,
        "ZEP_MULTI_CAST", VarbitID.ZEP_MULTI_CAST, "ZEP_MULTI_GNO", VarbitID.ZEP_MULTI_GNO,
        "ZEP_MULTI_CRAFT", VarbitID.ZEP_MULTI_CRAFT, "ZEP_MULTI_VARR", VarbitID.ZEP_MULTI_VARR,
        "SLAYER_HELM_UNLOCKED", VarbitID.SLAYER_HELM_UNLOCKED, "SLAYER_RING_UNLOCKED", VarbitID.SLAYER_RING_UNLOCKED,
        "SLAYER_AMMO_UNLOCKED", VarbitID.SLAYER_AMMO_UNLOCKED, "SLAYER_UNLOCK_SUPERIORMOBS", VarbitID.SLAYER_UNLOCK_SUPERIORMOBS,
        "SLAYER_UNLOCK_BOSSES", VarbitID.SLAYER_UNLOCK_BOSSES, "SLAYER_UNLOCK_STORAGE", VarbitID.SLAYER_UNLOCK_STORAGE,
        "SLAYER_UNLOCK_WILDY_EXTRATASKS", VarbitID.SLAYER_UNLOCK_WILDY_EXTRATASKS,
        "SLAYER_UNLOCK_REDDRAGONS", VarbitID.SLAYER_UNLOCK_REDDRAGONS,
        "SLAYER_UNLOCK_AVIANSIES", VarbitID.SLAYER_UNLOCK_AVIANSIES, "SLAYER_UNLOCK_TZHAAR", VarbitID.SLAYER_UNLOCK_TZHAAR,
        "SLAYER_UNLOCK_LIZARDMEN", VarbitID.SLAYER_UNLOCK_LIZARDMEN, "SLAYER_UNLOCK_BASILISK", VarbitID.SLAYER_UNLOCK_BASILISK,
        "SLAYER_UNLOCK_VAMPYRES", VarbitID.SLAYER_UNLOCK_VAMPYRES, "SLAYER_UNLOCK_WARPED_CREATURES", VarbitID.SLAYER_UNLOCK_WARPED_CREATURES,
        "SLAYER_UNLOCK_GRYPHONS", VarbitID.SLAYER_UNLOCK_GRYPHONS, "SLAYER_UNLOCK_AQUANITES", VarbitID.SLAYER_UNLOCK_AQUANITES,
        "SLAYER_UNLOCK_FOSSILWYVERNBLOCK", VarbitID.SLAYER_UNLOCK_FOSSILWYVERNBLOCK,
        "SLAYER_UNLOCK_HELM_BLACK", VarbitID.SLAYER_UNLOCK_HELM_BLACK, "SLAYER_UNLOCK_HELM_GREEN", VarbitID.SLAYER_UNLOCK_HELM_GREEN,
        "SLAYER_UNLOCK_HELM_RED", VarbitID.SLAYER_UNLOCK_HELM_RED, "SLAYER_UNLOCK_HELM_PURPLE", VarbitID.SLAYER_UNLOCK_HELM_PURPLE,
        "SLAYER_UNLOCK_HELM_TURQUOISE", VarbitID.SLAYER_UNLOCK_HELM_TURQUOISE, "SLAYER_UNLOCK_HELM_HYDRA", VarbitID.SLAYER_UNLOCK_HELM_HYDRA,
        "SLAYER_UNLOCK_HELM_TWISTED", VarbitID.SLAYER_UNLOCK_HELM_TWISTED, "SLAYER_UNLOCK_HELM_ARAXYTE", VarbitID.SLAYER_UNLOCK_HELM_ARAXYTE,
        "SLAYER_UNLOCK_HELM_HOODED", VarbitID.SLAYER_UNLOCK_HELM_HOODED,
        "SLAYER_LONGER_ABYSSALDEMONS", VarbitID.SLAYER_LONGER_ABYSSALDEMONS, "SLAYER_LONGER_BLACKDEMONS", VarbitID.SLAYER_LONGER_BLACKDEMONS,
        "SLAYER_LONGER_GREATERDEMONS", VarbitID.SLAYER_LONGER_GREATERDEMONS, "SLAYER_LONGER_BLACKDRAGONS", VarbitID.SLAYER_LONGER_BLACKDRAGONS,
        "SLAYER_LONGER_METALDRAGONS", VarbitID.SLAYER_LONGER_METALDRAGONS, "SLAYER_LONGER_DARKBEASTS", VarbitID.SLAYER_LONGER_DARKBEASTS,
        "SLAYER_LONGER_ANKOU", VarbitID.SLAYER_LONGER_ANKOU, "SLAYER_LONGER_SUQAH", VarbitID.SLAYER_LONGER_SUQAH,
        "SLAYER_LONGER_BLOODVELD", VarbitID.SLAYER_LONGER_BLOODVELD, "SLAYER_LONGER_ABERRANTSPECTRES", VarbitID.SLAYER_LONGER_ABERRANTSPECTRES,
        "SLAYER_LONGER_AVIANSIES", VarbitID.SLAYER_LONGER_AVIANSIES,
        "SLAYER_LONGER_CAVEHORRORS", VarbitID.SLAYER_LONGER_CAVEHORRORS, "SLAYER_LONGER_DUSTDEVILS", VarbitID.SLAYER_LONGER_DUSTDEVILS,
        "SLAYER_LONGER_SKELETALWYVERNS", VarbitID.SLAYER_LONGER_SKELETALWYVERNS, "SLAYER_LONGER_GARGOYLES", VarbitID.SLAYER_LONGER_GARGOYLES,
        "SLAYER_LONGER_NECHRYAEL", VarbitID.SLAYER_LONGER_NECHRYAEL, "SLAYER_LONGER_CAVEKRAKEN", VarbitID.SLAYER_LONGER_CAVEKRAKEN,
        "SLAYER_LONGER_SPIRITUALGWD", VarbitID.SLAYER_LONGER_SPIRITUALGWD, "SLAYER_LONGER_SCABARITES", VarbitID.SLAYER_LONGER_SCABARITES,
        "SLAYER_LONGER_FOSSILWYVERNS", VarbitID.SLAYER_LONGER_FOSSILWYVERNS,
        "SLAYER_LONGER_BASILISK", VarbitID.SLAYER_LONGER_BASILISK,
        "SLAYER_LONGER_VAMPYRES", VarbitID.SLAYER_LONGER_VAMPYRES, "SLAYER_LONGER_ARAXYTES", VarbitID.SLAYER_LONGER_ARAXYTES,
        "SLAYER_LONGER_REVENANTS", VarbitID.SLAYER_LONGER_REVENANTS, "SLAYER_LONGER_CUSTODIANS", VarbitID.SLAYER_LONGER_CUSTODIANS,
        "SLAYER_LONGER_WYRMS", VarbitID.SLAYER_LONGER_WYRMS, "SLAYER_LONGER_AQUANITES", VarbitID.SLAYER_LONGER_AQUANITES,
        "SLAYER_UNLOCK_LONGER_GRYPHON", VarbitID.SLAYER_UNLOCK_LONGER_GRYPHON,
        "SLAYER_UNLOCK_LONGER_FROST_DRAGONS", VarbitID.SLAYER_UNLOCK_LONGER_FROST_DRAGONS);

    /** KR_KNIGHTWAVES_STATE when the training ground is done. */
    static final int KNIGHT_WAVES = 8;

    private static Map<String, Integer> unlocks(Object... pairs)
    {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], (Integer) pairs[i + 1]);
        return Collections.unmodifiableMap(map);
    }

    private GameTravel() { }

    /** Client thread. Nothing when not logged in. */
    static Observation read(Client client, Supplier<Instant> clock)
    {
        if (client == null || client.getLocalPlayer() == null || clock == null)
            return Observation.notRead();
        return read(client::getVarbitValue, clock);
    }

    static Observation read(IntUnaryOperator varbit, Supplier<Instant> clock)
    {
        try
        {
            int spellbook = varbit.applyAsInt(VarbitID.SPELLBOOK);
            if (spellbook < 0 || spellbook > 3) return Observation.unavailable();
            JsonObject row = new JsonObject();
            row.addProperty("spellbook", spellbook);
            JsonObject fairyRings = new JsonObject();
            fairyRings.addProperty("permission", varbit.applyAsInt(VarbitID.FAIRYRING_PERMISSION) != 0);
            fairyRings.addProperty("zeahVisited", varbit.applyAsInt(VarbitID.ZEAH_PLAYERHASVISITED) != 0);
            fairyRings.addProperty("varlamoreVisited", varbit.applyAsInt(VarbitID.VARLAMORE_VISITED) != 0);
            row.add("fairyRings", fairyRings);
            JsonObject prayers = new JsonObject();
            prayers.addProperty("rigour", varbit.applyAsInt(VarbitID.PRAYER_RIGOUR_UNLOCKED) != 0);
            prayers.addProperty("augury", varbit.applyAsInt(VarbitID.PRAYER_AUGURY_UNLOCKED) != 0);
            prayers.addProperty("preserve", varbit.applyAsInt(VarbitID.PRAYER_PRESERVE_UNLOCKED) != 0);
            row.add("prayers", prayers);
            JsonObject unlocks = new JsonObject();
            for (Map.Entry<String, Integer> unlock : UNLOCKS.entrySet())
                unlocks.addProperty(unlock.getKey(), varbit.applyAsInt(unlock.getValue()) != 0);
            // Knight Waves isn't in the quest list: its state counts the knights beaten.
            unlocks.addProperty("KNIGHT_WAVES_DONE", varbit.applyAsInt(VarbitID.KR_KNIGHTWAVES_STATE) >= KNIGHT_WAVES);
            // The Zeal unlock varbit (unconfirmed with an unlocked character).
            unlocks.addProperty("PRAYER_ZEAL_UNLOCKED", varbit.applyAsInt(VarbitID.PRAYER_ZEAL_UNLOCKED) != 0);
            row.add("unlocks", unlocks);
            JsonArray rows = new JsonArray();
            rows.add(row);
            return new Observation(rows, ChangeAwareSourceCapture.time(clock.get()));
        }
        catch (RuntimeException unavailable)
        {
            return Observation.unavailable();
        }
    }
}
