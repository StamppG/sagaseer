# SagaSeer

SagaSeer is a free, fan-made Old School RuneScape progress tracker. This RuneLite plugin reads **your own** account's game facts from the client and uploads them to the SagaSeer server, and the website at https://www.sagaseer.com shows them to you. SagaSeer is not made by or affiliated with Jagex.

## Linking

**Accounts are invite-only during early access:** you need an invite code to create a SagaSeer account. Without one, the sidebar still works on this computer, and no game data is sent.

No game or account data is sent until you link the plugin to a SagaSeer account; pressing **Link to SagaSeer** contacts the server only to start the link. Before that the sidebar still works: it shows what the client shows now, your recent level-ups, quests, diaries, Collection Log items and kill counts, and keeps recent changes on this computer only (about 90 days, at most 8 MB, in `.runelite/sagaseer/prelink`). After linking, the website asks once whether to import them (Import History, with their original times) or delete them (Start Fresh). **Clear local data** in the sidebar deletes them at any time.

To link:

1. Open the SagaSeer sidebar panel and press **Link to SagaSeer**.
2. Approve the request on the page that opens in your browser (**Open the approval page** opens it again; **Cancel** stops the request). New here? That page creates your account; enter your invite code there.
3. The sidebar header then shows your character: "No upload yet" at first, then "● Synced" with the time of the last upload.

The link token is saved in the plugin's own folder under `.runelite`; the server keeps only a hash of it. **Forget link on this computer** deletes it and stops all uploads.

## Settings

- **Pause uploads** (off by default; also the sidebar's Pause button). While it is on, nothing is read, kept or sent, and the link is kept. Turn it off and uploads carry on as normal.
- **Data sent from this computer:** one checkbox per data type (all on by default). Unticking one stops this computer reading and sending it. Your account's Data & privacy settings on the website are the ceiling: what is off there stays off, and the server refuses it too.

## What is sent, and when

Uploads go to one fixed host, `https://api.sagaseer.com`, off the client thread. They happen when something changes in the game (login, a level-up, a quest or diary done, a bank close and similar) plus a check every 15 minutes, and they carry only what changed. Logging in and opening the bank send everything once.

| Section | What it holds |
|---|---|
| Skills, quests and quest points | Levels, experience and quest states; the most XP gained in one game tick per skill, seen while the plugin runs |
| Achievement diaries, combat achievements | Tier counts and task completion from varps and varbits |
| Account | Account type, the game's own death count and Collection Log totals |
| Deaths | When you died, what you were fighting, and the name of the area (a city, dungeon, boss lair or region, the one place-related fact SagaSeer keeps: never a tile or coordinates); with Inventory & equipment on, also which items left your inventory and gear, and which appeared, between the death and respawning |
| Inventory, equipment | Item ids, names and quantities; how many of each food you ate and potion you drank, counted from the game's messages while the plugin runs |
| Bank and other storage | The bank (read while it is open, sent when it closes), seed vault, looting bag, house costume-room chests and other containers the player opens |
| Kill counts, activities | The game's own kill, minigame and activity counters |
| Boss records | Kill counts and personal bests from the game's own messages |
| Collection Log | Pages you open, new-item messages, and the full log when you press the sync button |
| Slayer, Hunter rumours | Current task, points, streaks, block list; rumours and the rumour total |
| Observed counts | Slayer superiors that appeared (the game's own message, per task) and the kills, pickpockets and rewards RuneLite's Loot Tracker recorded, by name, counted per session while the plugin runs; never another player's loot |
| Raid rewards | Your own reward chest after a raid (for other players only whether someone got a purple, never a name or item) |
| Farming | Patch and bird-house states seen inside the farming areas |
| House | The house settings the game keeps (location, style, servant, servant pay) at each upload; the player's **own** house only (never a friend's): its rooms, the furniture built in them, the menagerie's pets, and the destinations the portal nexus's Teleport menu lists while it is open there |
| Group Ironman, clan, time played | Group and clan facts the game shows you (your group's name, size and leader; your clan and your own rank, never a member list); the account summary's time played |
| Music | Which music tracks you have unlocked, and your "Music area type" setting (Modern or Classic) |
| Travel | Your spellbook, teleport and travel permissions, and one-time unlocks (some prayers, quetzal and balloon routes, Slayer rewards); not your movements |
| Item charges | Charge counts on items that have them |
| Grand Exchange offers | Your own offer slots: state, item, quantity, price and amount spent |
| Grand Exchange history | The past trades the game lists in its own Grand Exchange history window, read once each time you open it: bought or sold, item, quantity, gp and tax (the window shows no time) |

SagaSeer records normal worlds and Hardcore Worlds (as their own characters, with each life kept when it ends). On beta, tournament, quest speedrunning, no-save and Fresh Start worlds it sends nothing. On Leagues and Deadman worlds it uploads no game data and keeps no website history: a linked plugin only checks once with the server (sending the character name, account hash and world kind), and the server declines. The sidebar's Recent Events still lists that world's changes, on this computer only.

The character name and the account hash RuneLite already has are sent so the server can tell your characters apart, with the kind of world each upload comes from (normal or Hardcore Worlds). Never sent: other players' names or items; what anyone types in public, private, clan or friends chat; anything outside RuneLite. Chat is read only for the game's own messages, to recognise the events above: game messages (`GAMEMESSAGE`, and the filterable `SPAM` ones: eating and drinking, Slayer superiors, item charges, raid and seed box lines), message boxes (`MESBOX`), the raid notices the game posts in `FRIENDSCHATNOTIFICATION` (the Chambers of Xeric purple line is compared with your own name and kept only as a yes/no, "someone else got a purple"), and a Guild Hunter's lines in the dialogue box (`DIALOG`), for the Hunter rumour. No typed chat and no teammate names are uploaded, and the message text itself is not uploaded.

## The Collection Log button

The plugin adds one button to the Collection Log screen, next to the close button, labelled **Show Me Your ClogHole** (a play on the log's nickname, "clog"); hovering it says "Sync your collection log with SagaSeer". Only when you press it, the plugin opens the log's Search once (the game then sends every obtained item, as WikiSync and RuneProfile use) and restores the normal view. Opening the log never does this by itself, and nothing repeats it.

## Privacy and contact

- Privacy policy: https://www.sagaseer.com/privacy
- Contact: support@sagaseer.com

## License

BSD 2-Clause; see `LICENSE` and `THIRD_PARTY_NOTICES.md`.
