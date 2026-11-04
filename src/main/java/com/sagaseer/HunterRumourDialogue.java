package com.sagaseer;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.client.util.Text;

/**
 * Hunter rumours from Guild Hunter dialogue (no var holds them). Hunter and tier come from the speaker line ("Guild Hunter Teco
 * (Expert)"); the creature from the current rumour ("There's been rumours of a rare dashing kebbit..."), another hunter's
 * reminder ("I hear you're hunting for Teco..."), a held rumour ("I asked you to hunt a dashing kebbit..."), or the line after a
 * switch offer ("Unless you fancy hunting for me instead?").
 */
final class HunterRumourDialogue
{
    static final class Rumour
    {
        final String hunter;
        final String tier;
        final String creature;
        /** True for a rumour a hunter keeps for the player, false for the current one. */
        final boolean held;

        Rumour(String hunter, String tier, String creature)
        {
            this(hunter, tier, creature, false);
        }

        Rumour(String hunter, String tier, String creature, boolean held)
        {
            this.hunter = hunter;
            this.tier = tier;
            this.creature = creature;
            this.held = held;
        }
    }

    /** The creature shown is the longest of these inside the text. */
    private static final String[] CREATURES = {
        "barb-tailed kebbit", "black chinchompa", "black salamander", "black warlock", "carnivorous chinchompa",
        "cerulean twitch", "common kebbit", "copper longtail", "crimson swift", "dark kebbit", "dashing kebbit",
        "embertailed jerboa", "feldip weasel", "golden warbler", "grey chinchompa", "herbiboar", "horned graahk",
        "moonlight antelope", "moonlight moth", "orange salamander", "polar kebbit", "prickly kebbit", "pyre fox",
        "razor-backed kebbit", "red chinchompa", "red salamander", "ruby harvest", "sabre-toothed kebbit",
        "sabre-toothed kyatt", "sapphire glacialis", "snowy knight", "spined larupia", "spotted kebbit",
        "sunlight antelope", "sunlight moth", "swamp lizard", "tecu salamander", "tropical wagtail", "wild kebbit",
        "ferret"};
    private static final String[] BY_LENGTH = Arrays.stream(CREATURES)
        .sorted(Comparator.comparingInt(String::length).reversed()).toArray(String[]::new);

    /** The guild's hunters and their tiers, to place a hunter another one names. */
    private static final Map<String, String> TIERS = Map.of("Gilman", "Novice", "Cervus", "Adept", "Ornus", "Adept",
        "Aco", "Expert", "Teco", "Expert", "Wolf", "Master");

    private static final int MAX_CREATURE = 60;
    private static final Pattern SPEAKER = Pattern.compile(
        "^(?:Guild Hunter|Huntmaster) ([A-Za-z][A-Za-z' -]{0,29}) \\(([A-Za-z]{1,20})\\)$");
    private static final Pattern ASSIGNED = Pattern.compile(
        "There[’']s been rumours of (?:an? )?(.+?) coming in\\.");
    private static final Pattern STATUS = Pattern.compile(
        "This rumour was about (?:an? )?(.+?)\\.(?:\\s|$)");
    private static final Pattern OTHER_HUNTERS = Pattern.compile(
        "I hear you[’']re hunting for (?:Guild Hunter |Huntmaster )?([A-Z][a-z]+)\\b.*?" +
        "Aren[’']t you looking for (?:an? )?(.+?) at the moment\\?");
    private static final Pattern STORED = Pattern.compile(
        "(?:I asked you to hunt|sending you off to hunt) (?:an? )?(.+?)(?:[.,?]| and bring back)", Pattern.CASE_INSENSITIVE);
    private static final Pattern OFFER = Pattern.compile("(?:instead\\?|would you prefer that one|a new one entirely\\?)", Pattern.CASE_INSENSITIVE);

    enum OfferKind { SWITCH, FRESH }

    private HunterRumourDialogue() { }

    /** The rumour a Guild Hunter's line names (current, or held when recalled), else null. */
    static Rumour parse(String speaker, String text)
    {
        if (speaker == null || text == null) return null;
        Matcher who = SPEAKER.matcher(clean(speaker));
        if (!who.matches()) return null;
        String line = clean(text);
        Matcher other = OTHER_HUNTERS.matcher(line);
        if (other.find())
        {
            String tier = TIERS.get(other.group(1));
            String creature = creature(other.group(2));
            return tier == null || creature == null ? null : new Rumour(other.group(1), tier, creature);
        }
        // Gilman names another hunter and the creature in a different sentence shape.
        if (line.toLowerCase(Locale.ROOT).contains("rumour") && (line.contains("following") || line.contains("hunting for")))
            for (String hunter : TIERS.keySet()) if (!hunter.equals(who.group(1)) && line.matches("(?i).*\\b" + hunter + "(?:'s)?\\b.*"))
            {
                String named = knownCreature(line);
                if (named != null) return new Rumour(hunter, TIERS.get(hunter), named);
            }
        Matcher stored = STORED.matcher(line);
        if (stored.find())
        {
            String creature = creature(stored.group(1));
            return creature == null ? null : new Rumour(who.group(1), who.group(2), creature, true);
        }
        Matcher said = ASSIGNED.matcher(line);
        if (!said.find())
        {
            said = STATUS.matcher(line);
            if (!said.find()) return null;
        }
        String creature = creature(said.group(1));
        return creature == null ? null : new Rumour(who.group(1), who.group(2), creature);
    }

    /** The hunter offering a switch, or null. */
    static String offeredBy(String speaker, String text)
    {
        String who = hunterOf(speaker);
        return who != null && text != null && OFFER.matcher(clean(text)).find() ? who : null;
    }

    static OfferKind offerKind(String text)
    {
        if (text == null || !OFFER.matcher(clean(text)).find()) return null;
        return clean(text).toLowerCase(Locale.ROOT).contains("a new one entirely?") ? OfferKind.FRESH : OfferKind.SWITCH;
    }

    /** The Guild Hunter speaking, or null. */
    static String hunterOf(String speaker)
    {
        if (speaker == null) return null;
        Matcher who = SPEAKER.matcher(clean(speaker));
        return who.matches() ? who.group(1) : null;
    }

    /** The listed creature found in the text, else the text minus a leading "rare". Null if unusable. */
    static String creature(String text)
    {
        String lower = text.toLowerCase(Locale.ROOT).trim();
        for (String creature : BY_LENGTH)
            if (lower.contains(creature)) return creature;
        String bare = lower.replaceFirst("^rare\\s+", "").trim();
        return bare.isEmpty() || bare.length() > MAX_CREATURE ? null : bare;
    }

    private static String knownCreature(String text)
    {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String creature : BY_LENGTH) if (lower.contains(creature)) return creature;
        return null;
    }

    private static String clean(String text)
    {
        return Text.removeTags(text.replaceAll("(?i)<br\\s*/?>", " ")).replaceAll("\\s+", " ").trim();
    }
}
