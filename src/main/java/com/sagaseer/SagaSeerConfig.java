package com.sagaseer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(SagaSeerConfig.GROUP)
public interface SagaSeerConfig extends Config
{
	String GROUP = "sagaseer";

	@ConfigItem(
			keyName = "pauseUploads",
			name = "Pause uploads",
			description = "While this is on, SagaSeer sends and keeps nothing. Your link is kept; turn it off to carry on.",
			position = 0
	)
	default boolean pauseUploads()
	{
		return false;
	}

	/** docs/pages/sync-controls.md: these only turn more off; what your account turned off on the website stays off. */
	@ConfigSection(
			name = "Data sent from this computer",
			description = "Turn data types off for this computer only. Your account's Data & privacy settings on the website still apply.",
			position = 1
	)
	String DATA_SECTION = "dataSent";

	@ConfigItem(
			keyName = "sendSkills",
			name = "Skills",
			description = "Levels and experience. Untick to stop this computer reading and sending it.",
			section = DATA_SECTION,
			position = 1
	)
	default boolean sendSkills()
	{
		return true;
	}

	@ConfigItem(
			keyName = "sendItems",
			name = "Inventory & equipment",
			description = "What you carry and wear. Untick to stop this computer reading and sending it.",
			section = DATA_SECTION,
			position = 2
	)
	default boolean sendItems()
	{
		return true;
	}

	@ConfigItem(
			keyName = "sendStorage",
			name = "Bank & storage",
			description = "Your bank, the other containers you open and your rune pouch. Untick to stop this computer reading and sending it.",
			section = DATA_SECTION,
			position = 3
	)
	default boolean sendStorage()
	{
		return true;
	}

	@ConfigItem(
			keyName = "sendQuests",
			name = "Quests",
			description = "Quest states and quest points. Untick to stop this computer reading and sending it.",
			section = DATA_SECTION,
			position = 4
	)
	default boolean sendQuests()
	{
		return true;
	}

	@ConfigItem(
			keyName = "sendDiaries",
			name = "Achievement Diaries",
			description = "Diary tiers and tasks. Untick to stop this computer reading and sending it.",
			section = DATA_SECTION,
			position = 5
	)
	default boolean sendDiaries()
	{
		return true;
	}

	@ConfigItem(
			keyName = "sendCombat",
			name = "Combat",
			description = "Kill counts, personal bests and Combat Achievements. Untick to stop this computer reading and sending it.",
			section = DATA_SECTION,
			position = 6
	)
	default boolean sendCombat()
	{
		return true;
	}

	@ConfigItem(
			keyName = "sendCollectionLog",
			name = "Collection Log",
			description = "Collection Log items and pages. Untick to stop this computer reading and sending it.",
			section = DATA_SECTION,
			position = 7
	)
	default boolean sendCollectionLog()
	{
		return true;
	}

	@ConfigItem(
			keyName = "sendSlayer",
			name = "Slayer & Hunter rumours",
			description = "Slayer task, points and block list; Hunter rumours. Untick to stop this computer reading and sending it.",
			section = DATA_SECTION,
			position = 8
	)
	default boolean sendSlayer()
	{
		return true;
	}

	@ConfigItem(
			keyName = "sendActivities",
			name = "Activities",
			description = "Minigame and activity counters. Untick to stop this computer reading and sending it.",
			section = DATA_SECTION,
			position = 9
	)
	default boolean sendActivities()
	{
		return true;
	}

	@ConfigItem(
			keyName = "sendFarming",
			name = "Farming",
			description = "Patch and bird-house states. Untick to stop this computer reading and sending it.",
			section = DATA_SECTION,
			position = 10
	)
	default boolean sendFarming()
	{
		return true;
	}

	@ConfigItem(
			keyName = "sendRaids",
			name = "Raid rewards",
			description = "Your own raid reward chests. Untick to stop this computer reading and sending it.",
			section = DATA_SECTION,
			position = 11
	)
	default boolean sendRaids()
	{
		return true;
	}

	@ConfigItem(
			keyName = "sendGim",
			name = "Group Ironman",
			description = "Group facts the game shows you. Untick to stop this computer reading and sending it.",
			section = DATA_SECTION,
			position = 12
	)
	default boolean sendGim()
	{
		return true;
	}

	@ConfigItem(
			keyName = "sendClan",
			name = "Clan",
			description = "The name of your own clan and your own rank in it, never other members. Untick to stop this computer reading and sending it.",
			section = DATA_SECTION,
			position = 13
	)
	default boolean sendClan()
	{
		return true;
	}

	@ConfigItem(
			keyName = "sendTravel",
			name = "Travel & spellbook",
			description = "Your travel unlocks (fairy rings, spirit trees, gliders, house portals) and current spellbook and prayer book. Untick to stop this computer reading and sending it.",
			section = DATA_SECTION,
			position = 14
	)
	default boolean sendTravel()
	{
		return true;
	}

	@ConfigItem(
			keyName = "sendMusic",
			name = "Music",
			description = "The music tracks you have unlocked. Untick to stop this computer reading and sending it.",
			section = DATA_SECTION,
			position = 15
	)
	default boolean sendMusic()
	{
		return true;
	}

	@ConfigItem(
			keyName = "sendHouse",
			name = "House",
			description = "Your house's settings (location, style, servant) at each upload; its rooms, furniture and menagerie pets only when you enter your own house yourself, and the destinations your portal nexus's Teleport menu lists only when you open it there. Untick to stop this computer reading and sending it.",
			section = DATA_SECTION,
			position = 16
	)
	default boolean sendHouse()
	{
		return true;
	}

	@ConfigItem(
			keyName = "sendAccount",
			name = "Account details",
			description = "Account type, deaths, Collection Log totals and time played. Untick to stop this computer reading and sending it.",
			section = DATA_SECTION,
			position = 17
	)
	default boolean sendAccount()
	{
		return true;
	}
}
