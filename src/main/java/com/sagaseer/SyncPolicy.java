package com.sagaseer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which sections make up each data type, the same table as the server's. The account's choice is the ceiling; this computer's
 * settings can only turn more off.
 */
final class SyncPolicy
{
    static final Map<String, List<String>> CATEGORIES = new LinkedHashMap<>();
    static
    {
        CATEGORIES.put("skills", List.of("state.skills", "state.xpHits"));
        CATEGORIES.put("items", List.of("state.inventory", "state.equipment", "state.consumables"));
        CATEGORIES.put("storage", List.of("state.storage", "state.itemCharges", "state.grandExchange", "state.grandExchangeHistory", "state.stash", "state.stashBoard"));
        CATEGORIES.put("quests", List.of("state.quests", "state.questPoints"));
        CATEGORIES.put("diaries", List.of("state.diaries"));
        CATEGORIES.put("combat", List.of("state.bosses", "state.killCounts", "state.combatAchievements"));
        CATEGORIES.put("collectionLog", List.of("state.collectionLog", "state.logAnnouncements", "state.dropAttempts"));
        CATEGORIES.put("slayer", List.of("state.slayer", "state.hunterRumours", "state.slayerSuperiors", "state.killLog"));
        CATEGORIES.put("activities", List.of("state.activities"));
        CATEGORIES.put("farming", List.of("state.farming"));
        CATEGORIES.put("raids", List.of("state.raidRewards"));
        CATEGORIES.put("gim", List.of("state.gim"));
        CATEGORIES.put("clan", List.of("state.clan"));
        CATEGORIES.put("travel", List.of("state.travel"));
        CATEGORIES.put("music", List.of("state.music"));
        CATEGORIES.put("house", List.of("state.house"));
        CATEGORIES.put("account", List.of("state.account", "state.timePlayed", "state.deaths"));
    }

    /** The names the website and the plugin's settings use. */
    static final Map<String, String> LABELS = new LinkedHashMap<>();
    static
    {
        String[] names = {"Skills", "Inventory & equipment", "Bank & storage", "Quests", "Achievement Diaries", "Combat",
            "Collection Log", "Slayer & Hunter rumours", "Activities", "Farming", "Raid rewards", "Group Ironman",
            "Clan", "Travel & spellbook", "Music", "House", "Account details"};
        int i = 0;
        for (String id : CATEGORIES.keySet()) LABELS.put(id, names[i++]);
    }

    /** Sections whose history the website shows: the only ones kept before linking. */
    static final Set<String> PRELINK_SECTIONS = Set.of("state.skills", "state.quests", "state.diaries",
        "state.combatAchievements", "state.bosses", "state.killCounts", "state.collectionLog", "state.account",
        "state.slayer", "state.raidRewards", "state.farming", "state.activities", "state.logAnnouncements");

    private SyncPolicy() { }

    /** The sections of the given categories. */
    static Set<String> sectionsOf(Collection<String> categories)
    {
        Set<String> sections = new LinkedHashSet<>();
        for (String category : categories)
        {
            List<String> listed = CATEGORIES.get(category);
            if (listed != null) sections.addAll(listed);
        }
        return sections;
    }

    /** Data types turned off in this computer's settings, as category ids. */
    static List<String> localOff(SagaSeerConfig config)
    {
        if (config == null) return List.of();
        boolean[] on = {config.sendSkills(), config.sendItems(), config.sendStorage(), config.sendQuests(),
            config.sendDiaries(), config.sendCombat(), config.sendCollectionLog(), config.sendSlayer(),
            config.sendActivities(), config.sendFarming(), config.sendRaids(), config.sendGim(), config.sendClan(), config.sendTravel(), config.sendMusic(),
            config.sendHouse(), config.sendAccount()};
        List<String> ids = List.copyOf(CATEGORIES.keySet());
        List<String> off = new ArrayList<>();
        for (int i = 0; i < on.length; i++) if (!on[i]) off.add(ids.get(i));
        return off;
    }
}
