package com.sagaseer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sagaseer.ChangeAwareSourceCapture.Observation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarbitID;

/**
 * The state.itemCharges rows: the game's CHARGES_*_QUANTITY counters, one row per counter above 0, read at every snapshot and
 * uploaded only when changed (Bank & storage). The website matches counters to held items.
 */
final class ItemCharges
{
    static final Map<String, Integer> COUNTERS = new LinkedHashMap<>();
    static
    {
        COUNTERS.put("TRIDENT_OF_THE_SEAS", VarbitID.CHARGES_TRIDENT_OF_THE_SEAS_QUANTITY);
        COUNTERS.put("TRIDENT_OF_THE_SEAS_E", VarbitID.CHARGES_TRIDENT_OF_THE_SEAS_E_QUANTITY);
        COUNTERS.put("SANGUINESTI_STAFF", VarbitID.CHARGES_SANGUINESTI_STAFF_QUANTITY);
        COUNTERS.put("TUMEKENS_SHADOW", VarbitID.CHARGES_TUMEKENS_SHADOW_QUANTITY);
        COUNTERS.put("TOXIC_BLOWPIPE", VarbitID.CHARGES_TOXIC_BLOWPIPE_QUANTITY);
        COUNTERS.put("SERPENTINE_HELM", VarbitID.CHARGES_SERPENTINE_HELM_QUANTITY);
        COUNTERS.put("TOXIC_STAFF_OF_THE_DEAD", VarbitID.CHARGES_TOXIC_STAFF_OF_THE_DEAD_QUANTITY);
        COUNTERS.put("BOW_OF_FAERDHINEN", VarbitID.CHARGES_BOW_OF_FAERDHINEN_QUANTITY);
        COUNTERS.put("BLADE_OF_SAELDOR", VarbitID.CHARGES_BLADE_OF_SAELDOR_QUANTITY);
        COUNTERS.put("CRYSTAL_ARMOUR", VarbitID.CHARGES_CRYSTAL_ARMOUR_QUANTITY);
        COUNTERS.put("CRYSTAL_TOOLS", VarbitID.CHARGES_CRYSTAL_TOOLS_QUANTITY);
        COUNTERS.put("VENATOR_BOW", VarbitID.CHARGES_VENATOR_BOW_QUANTITY);
        COUNTERS.put("BRACELET_OF_ETHEREUM", VarbitID.CHARGES_BRACELET_OF_ETHEREUM_QUANTITY);
        COUNTERS.put("TONALZTICS_OF_RALOS", VarbitID.CHARGES_TONALZTICS_OF_RALOS_QUANTITY);
        COUNTERS.put("WARPED_SCEPTRE", VarbitID.CHARGES_WARPED_SCEPTRE_QUANTITY);
        COUNTERS.put("EYE_OF_AYAK", VarbitID.CHARGES_EYE_OF_AYAK_QUANTITY);
        COUNTERS.put("DIZANAS_QUIVER", VarbitID.CHARGES_DIZANAS_QUIVER_QUANTITY);
        COUNTERS.put("CELESTIAL_RING", VarbitID.CHARGES_CELESTIAL_RING_QUANTITY);
        COUNTERS.put("XERICS_TALISMAN", VarbitID.CHARGES_XERICS_TALISMAN_QUANTITY);
        COUNTERS.put("PENDANT_OF_ATES", VarbitID.CHARGES_PENDANT_OF_ATES_QUANTITY);
        COUNTERS.put("TOME_OF_FIRE", VarbitID.CHARGES_TOME_OF_FIRE_QUANTITY);
        COUNTERS.put("TOME_OF_WATER", VarbitID.CHARGES_TOME_OF_WATER_QUANTITY);
        COUNTERS.put("TOME_OF_EARTH", VarbitID.CHARGES_TOME_OF_EARTH_QUANTITY);
        COUNTERS.put("ZORYAS_TOME", VarbitID.CHARGES_ZORYAS_TOME_QUANTITY);
        COUNTERS.put("GIANTSOUL_AMULET", VarbitID.CHARGES_GIANTSOUL_AMULET_QUANTITY);
        COUNTERS.put("OBLIGATOR", VarbitID.CHARGES_OBLIGATOR_QUANTITY);
        COUNTERS.put("BRYOPHYTAS_STAFF", VarbitID.CHARGES_BRYOPHYTAS_STAFF_QUANTITY);
        COUNTERS.put("BLOOD_FURY", VarbitID.CHARGES_BLOOD_FURY_QUANTITY);
        COUNTERS.put("WILDERNESS_WEAPON", VarbitID.CHARGES_WILDERNESS_WEAPON_QUANTITY);
        COUNTERS.put("SAILORS_AMULET", VarbitID.CHARGES_SAILORS_AMULET_QUANTITY);
        COUNTERS.put("HORN_OF_PLENTY", VarbitID.CHARGES_HORN_OF_PLENTY_QUANTITY);
        COUNTERS.put("ARCHIVISTS_FOLIO", VarbitID.CHARGES_ARCHIVISTS_FOLIO_QUANTITY);
        // A six-charge Book of the Dead reads 6 here (varbit 6035).
        COUNTERS.put("KHAREDSTS_MEMOIRS", VarbitID.VEOS_MEMOIR_CHARGES);
        COUNTERS.put("RING_OF_SUFFERING", VarbitID.CHARGES_RING_OF_SUFFERING_QUANTITY);
        COUNTERS.put("ARCLIGHT", VarbitID.CHARGES_ARCLIGHT_QUANTITY);
        COUNTERS.put("ASH_SANCTIFIER", VarbitID.CHARGES_ASH_SANCTIFIER_QUANTITY);
        COUNTERS.put("BONECRUSHER", VarbitID.CHARGES_BONECRUSHER_QUANTITY);
        COUNTERS.put("SOUL_BEARER", VarbitID.CHARGES_SOUL_BEARER_QUANTITY);
        COUNTERS.put("CIRCLET_OF_WATER", VarbitID.CHARGES_CIRCLET_OF_WATER_QUANTITY);
        COUNTERS.put("ECHO_BOOTS", VarbitID.CHARGES_ECHO_BOOTS_QUANTITY);
        COUNTERS.put("ALCHEMISTS_AMULET", VarbitID.CHARGES_ALCHEMISTS_AMULET_QUANTITY);
        COUNTERS.put("COWBELL_AMULET", VarbitID.CHARGES_COWBELL_AMULET_QUANTITY);
        COUNTERS.put("RING_OF_SHADOWS", VarbitID.CHARGES_RING_OF_SHADOWS_QUANTITY);
        // CRYSTAL_WEAK stays 0 while crystal items hold charges and matches no item, so it is not read.
    }

    /**
     * Any item's Check line, by one rule: many counters stay 0 whatever the item holds and some items have none, so charges can
     * always come from Check. The row's counter is CHECK_PREFIX plus the item id.
     */
    static final String CHECK_PREFIX = "ITEM_";
    /**
     * The Check line, e.g. "Your arclight has 3840 charges left, and is 1% infused."; a second sentence is allowed and ignored.
     * Groups: noun, count.
     */
    private static final String COUNT = "([0-9][0-9,]{0,12}|no|one|two)";
    private static final Pattern CHECK_LINE = Pattern.compile(
        "^(?:The|Your) ([A-Za-z' ]{1,40}?) (?:currently )?has " + COUNT + " (?:[a-z]{1,12} )?charges?"
            + "(?: remaining| left(?:, and is [0-9]{1,3}% infused)?)?\\.(?: [A-Z][^<>]{0,100}\\.)?$");
    /**
     * The other shape (from the Item Charges Improved plugin's list): "You have 23 charges left on the staff." Groups: count,
     * noun.
     */
    private static final Pattern YOU_HAVE_LINE = Pattern.compile(
        "^You have " + COUNT + " (?:[a-z]{1,12} )?charges? left (?:in|on|before) (?:the|your) ([A-Za-z' ]{1,40}?)(?: disintegrates)?\\.$");

    static String[] checkLine(String message)
    {
        String text = message == null ? "" : message.trim();
        Matcher has = CHECK_LINE.matcher(text);
        if (has.matches()) return new String[] {has.group(1), has.group(2)};
        Matcher left = YOU_HAVE_LINE.matcher(text);
        return left.matches() ? new String[] {left.group(2), left.group(1)} : null;
    }

    private static int count(String said)
    {
        switch (said)
        {
            case "no": return 0;
            case "one": return 1;
            case "two": return 2;
            default: return Integer.parseInt(said.replace(",", ""));
        }
    }

    /** True when every word the line names the item by is in the checked item's name ("ring" fits "Ring of suffering (ri)"). */
    static boolean names(String said, String itemName)
    {
        Set<String> words = new HashSet<>(Arrays.asList(wordsOf(itemName)));
        String[] named = wordsOf(said);
        return named.length > 0 && words.containsAll(Arrays.asList(named));
    }

    private static String[] wordsOf(String text)
    {
        String plain = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z' ]", " ").trim();
        return plain.isEmpty() ? new String[0] : plain.split(" +");
    }

    private ItemCharges() { }

    /** With the 43 counters, this keeps the section under the Backend's 100 rows. */
    static final int CHECK_ROWS_MAX = 50;

    static final class CheckChat
    {
        private static final class Waiting
        {
            final String counter;
            final String itemName;
            final Instant at;

            Waiting(String counter, String itemName, Instant at)
            {
                this.counter = counter;
                this.itemName = itemName;
                this.at = at;
            }
        }

        private final Map<String, Integer> pending = new TreeMap<>();
        private final List<Waiting> waiting = new ArrayList<>();
        private final Map<String, Long> checked = new HashMap<>();
        private long sequence;

        /** A Check click waits a few seconds for its line; any other click ends the wait. */
        void clicked(String option, int itemId, String name, Instant at)
        {
            if (!"Check".equals(option) || itemId <= 0 || name == null) { waiting.clear(); return; }
            waiting.removeIf(click -> at.isAfter(click.at.plusSeconds(5)));
            waiting.add(new Waiting(CHECK_PREFIX + itemId, name, at));
        }

        /**
         * True when the message is the Check line of exactly one waiting item. A line fitting two waiting items is dropped, never
         * guessed. Player chat never counts.
         */
        boolean accept(ChatMessageType type, String message, Instant at)
        {
            if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM) return false;
            waiting.removeIf(click -> at.isAfter(click.at.plusSeconds(5)));
            String[] line = checkLine(message);
            if (line == null) return false;
            List<Waiting> fits = new ArrayList<>();
            for (Waiting click : waiting) if (names(line[0], click.itemName)) fits.add(click);
            if (fits.isEmpty()) return false;
            waiting.removeAll(fits);
            if (fits.stream().map(click -> click.counter).distinct().count() > 1) return false;
            try
            {
                pending.put(fits.get(0).counter, count(line[1]));
                checked.put(fits.get(0).counter, ++sequence);
                return true;
            }
            catch (NumberFormatException tooLong) { return false; }
        }

        void reset()
        {
            pending.clear();
            waiting.clear();
            checked.clear();
        }
    }

    /**
     * The fresh read plus earlier Check readings (at most CHECK_ROWS_MAX, newest first) with new charges applied. Rows: COUNTERS
     * order, then Check readings by item id.
     */
    static Observation withChecked(Observation fresh, CheckChat chat)
    {
        if (!fresh.observed()) return fresh;
        Map<String, Integer> said = new TreeMap<>(chat.pending);
        Map<String, Long> recent = new HashMap<>(chat.checked);
        chat.pending.clear();
        return Observation.merging(previous -> {
            Map<String, JsonObject> rows = new TreeMap<>();
            for (JsonElement row : fresh.value) rows.put(row.getAsJsonObject().get("counter").getAsString(), row.getAsJsonObject());
            if (previous != null)
                for (JsonElement row : previous)
                {
                    String counter = row.getAsJsonObject().get("counter").getAsString();
                    if (counter.startsWith(CHECK_PREFIX) && !rows.containsKey(counter)) rows.put(counter, row.getAsJsonObject());
                }
            for (Map.Entry<String, Integer> check : said.entrySet())
            {
                if (check.getValue() <= 0) { rows.remove(check.getKey()); continue; }
                JsonObject row = new JsonObject();
                row.addProperty("counter", check.getKey());
                row.addProperty("charges", check.getValue());
                rows.put(check.getKey(), row);
            }
            Comparator<String> byId = Comparator.comparingInt(counter -> Integer.parseInt(counter.substring(CHECK_PREFIX.length())));
            Comparator<String> newestFirst = Comparator.<String>comparingLong(counter -> -recent.getOrDefault(counter, 0L)).thenComparing(byId.reversed());
            List<String> kept = rows.keySet().stream().filter(counter -> counter.startsWith(CHECK_PREFIX))
                .sorted(newestFirst).limit(CHECK_ROWS_MAX).sorted(byId).collect(Collectors.toList());
            JsonArray merged = new JsonArray();
            for (String counter : COUNTERS.keySet()) if (rows.containsKey(counter)) merged.add(rows.get(counter));
            for (String counter : kept) merged.add(rows.get(counter));
            return merged;
        }, fresh.sourceObservedAt);
    }

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
            JsonArray rows = new JsonArray();
            for (Map.Entry<String, Integer> counter : COUNTERS.entrySet())
            {
                int charges = varbit.applyAsInt(counter.getValue());
                if (charges <= 0) continue;
                JsonObject row = new JsonObject();
                row.addProperty("counter", counter.getKey());
                row.addProperty("charges", charges);
                rows.add(row);
            }
            return new Observation(rows, ChangeAwareSourceCapture.time(clock.get()));
        }
        catch (RuntimeException unavailable)
        {
            return Observation.unavailable();
        }
    }
}
