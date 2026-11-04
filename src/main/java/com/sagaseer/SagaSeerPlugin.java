package com.sagaseer;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.util.Locale;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Filepath;
import okhttp3.OkHttpClient;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import net.runelite.api.Client;
import net.runelite.api.WorldType;
import net.runelite.api.GameState;
import net.runelite.api.ChatMessageType;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.Item;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.MenuAction;
import net.runelite.api.FontID;

import net.runelite.api.Actor;
import net.runelite.api.Player;
import net.runelite.api.NPC;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.ClanChannelChanged;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.clan.ClanID;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;

import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.ScriptID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.JavaScriptCallback;

import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.util.Text;

import javax.swing.SwingUtilities;
import java.time.Instant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.EnumSet;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@PluginDescriptor(
		name = "SagaSeer",
		description = "Tracks your account's progress on the SagaSeer website",
		internalName = "sagaseer"
)
public class SagaSeerPlugin extends Plugin
{
	// The game's eat and drink lines, SPAM included. The name ends at the first full stop: the game can add a second sentence or
	// a potion's doses left.
	static final Pattern EAT_LINE = Pattern.compile("^You eat the ([^.]+)\\..*$");
	static final Pattern DRINK_LINE = Pattern.compile("^You drink some of your ([^.]+)\\..*$");
	public SagaSeerPlugin()
	{
		this(new DeferredSnapshotScheduler());
	}

	SagaSeerPlugin(DeferredSnapshotScheduler scheduler)
	{
		this.snapshotScheduler = scheduler;
	}

	GameTriggers gameTriggers()
	{
		return gameTriggers;
	}

	RaidRewards raidRewards()
	{
		return raidRewards;
	}

	GamePatches gamePatches()
	{
		return gamePatches;
	}

	private boolean farmingTick()
	{
		if (!reads("state.farming")) return false;
		LocalPoint local = client.getLocalPlayer().getLocalLocation();
		if (local == null) return false;
		WorldPoint at = WorldPoint.fromLocalInstance(client, local);
		return gamePatches.tick(currentCollectionLogName(), at.getRegionID(), at.getX(), at.getY(), at.getPlane(),
			client::getVarbitValue, client::getVarpValue, Instant.now());
	}

	private boolean stashTick()
	{
		if (stashWantedAt < 0) return false;
		if (!reads("state.stash"))
		{
			stashWantedAt = -1;
			return false;
		}
		String account = client.getLocalPlayer().getName();
		if (account == null || account.isBlank()) return false; // the name can arrive a moment after the player
		if (!stashUnits.running())
		{
			if (currentGameTick() < stashWantedAt) return false;
			stashUnits.request(account);
		}
		boolean changed = stashUnits.tick(account, this::stashCheck, Instant::now);
		if (!stashUnits.running()) stashWantedAt = -1;
		return changed;
	}

	private boolean stashBoardTick()
	{
		if (!stashBoard.open() || !reads("state.stashBoard")) return false;
		return stashBoard.tick(client.getLocalPlayer().getName(), component ->
		{
			Widget list = client.getWidget(component);
			return list == null ? null : list.getChildren();
		}, Instant::now);
	}

	private boolean killLogTick()
	{
		if (!killLog.open() || !reads("state.killLog")) return false;
		return killLog.tick(client.getLocalPlayer().getName(), client::getWidget, Instant::now);
	}

	/** One unit through the game's own check script, as RuneLite's clue scroll plugin runs it. */
	private int stashCheck(int objectId)
	{
		client.runScript(ScriptID.WATSON_STASH_UNIT_CHECK, objectId, 0, 0, 0);
		int[] stack = client.getIntStack();
		return stack == null || client.getIntStackSize() < 1 || stack.length < 1 ? -1 : stack[0];
	}

	boolean inInstancedRegion()
	{
		return client != null && client.isInInstancedRegion();
	}

	boolean hasLocalPlayer()
	{
		return client.getLocalPlayer() != null;
	}

	int currentGameTick()
	{
		return client.getTickCount();
	}

	String bossRecordCaptureSelector()
	{
		return characterKey();
	}

	/** Kill counts are held while they can go somewhere: uploaded when linked, kept locally when not. */
	boolean bossRecordCaptureAllowed()
	{
		return !uploadsPaused() && (linkedCredential() != null || localTrail != null);
	}

	Instant bossRecordObservedAt()
	{
		return Instant.now();
	}

	List<BossRecordChatParser.CountFact> bossRecordPendingCounts()
	{
		return bossRecordChatCapture.pendingCounts(bossRecordCaptureSelector(),
				collectionLogIsLoggedIn() && bossRecordCaptureAllowed());
	}

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private SagaSeerConfig config;

	@Inject
	private Gson gson;

	private final InventorySource changeAwareInventorySource = new InventorySource();
	private final InventorySource changeAwareEquipmentSource = new InventorySource(14);
	private ChangeAwareEngineBridge changeAwareBridge;

	@Inject
	private OkHttpClient httpClient;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ItemManager itemManager;

	private volatile SagaSeerLinkPanel linkPanel;
	private volatile LocalTrail localTrail;
	private final PanelPull panelPull = new PanelPull();
	private volatile PanelRead panelRead;
	/** Bumped at each session end and link change so an older answer is never shown; checked and kept under panelLock. */
	private int panelSession;
	private final Object panelLock = new Object();
	private volatile boolean snapshotSyncPaused;
	/** Set by a bank open: the next upload re-sends every section whole. */
	private volatile boolean fullResyncWanted;
	private ExecutorService linkIo;
	private volatile int lifecycle;
	private static final String MEMBERS_MARK = " (Members)";
	private static final long MIN_UPLOAD_INTERVAL_MS = 1000L;
	private static final int ENTRY_SNAPSHOT_SETTLE_TICKS = 3;
	/** Lets the kill varp, Loot Tracker attempt and drop arrive before the upload. */
	private static final int BOSS_RECORD_SETTLE_TICKS = 2;
	static final int MODE_SETTLE_TICKS = ENTRY_SNAPSHOT_SETTLE_TICKS;
	static final int LMS_IN_GAME_VARBIT = VarbitID.BR_INGAME;
	static final int PVP_ARENA_FIGHT_INTERFACE = InterfaceID.PvpArenaHud.UNIVERSE;
	private volatile WorldMode.Scope worldScope;
	private volatile boolean modeGuardActive;
	private volatile String worldAccount;
	private WorldMode.Scope lastFixedScope;
	private int modeReadyTick = -1;
	private boolean lastLmsArea;
	private boolean lastArenaArea;
	private static final int UI_SNAPSHOT_SETTLE_TICKS = 1;

	private long lastUploadTime = 0;
	private final DeferredSnapshotScheduler snapshotScheduler;
	/** Kills left last seen, so only a task starting or ending uploads (-1: none seen). */
	private volatile int slayerKillsLeft = -1;
	private final RaidRewards raidRewards = new RaidRewards();
	private final GamePatches gamePatches = new GamePatches();
	private final GameMusic gameMusic = new GameMusic(log::debug);
	private boolean lastLoadingIntoInstance;
	private final BossRecordChatCapture bossRecordChatCapture = new BossRecordChatCapture();
	private final GameActivities.ClueChat clueChat = new GameActivities.ClueChat();
	private final ItemCharges.CheckChat checkChat = new ItemCharges.CheckChat();
	private final GameActivities.Tears tears = new GameActivities.Tears();
	private final LogAnnouncements logAnnouncements = new LogAnnouncements();
	private final DeathDetails deathDetails = new DeathDetails();
	private final DeathItems deathItems = new DeathItems();
	private final DeathCause deathCause = new DeathCause();
	private final ObservedCounts observedCounts = new ObservedCounts();
	private final HouseCapture house = new HouseCapture();
	private final GrandExchangeHistory grandExchangeHistory = new GrandExchangeHistory();
	private final EntryObservationSettleGuard entryObservationSettleGuard = new EntryObservationSettleGuard();

	private final StorageLocations storageLocations = new StorageLocations();

	private String entryIntentReason = null;
	/**
	 * state.clan reports "outside a clan" only once the game has had time to send the clan settings, or after the channel went
	 * away while logged in.
	 */
	private static final int CLAN_SETTLE_TICKS = 50;
	private long clanSettledTick = -1L;
	private boolean clanLeft = false;
	private String pendingEntrySnapshotReason = null;
	private boolean lootingBagWidgetWasVisible = false;
	private boolean grandExchangeSent;

	private final CollectionLogAccountState collectionLogState = new CollectionLogAccountState();
	private CollectionLogLayout collectionLogLayout;
	private final HunterRumourState hunterRumourState = new HunterRumourState();
	private final PlayTimeState playTimeState = new PlayTimeState();
	private final GameTriggers gameTriggers = new GameTriggers();
	private final StashUnits stashUnits = new StashUnits();
	private final StashBoard stashBoard = new StashBoard();
	private final KillLog killLog = new KillLog();
	private int stashWantedAt = -1;
	private static final int STASH_LOGIN_DELAY_TICKS = 10;

	/*
	 * Collection Log sync, the technique WikiSync and RuneProfile use: the game sends every obtained item (script 4100) only when
	 * the log's Search opens. The plugin's button opens Search once; script 2240 restores the view.
	 */
	private static final int COLLECTION_DELAYED_TRANSMIT = 4100;
	private static final int COLLECTION_LOG_SETUP = 7797;
	private static final int COLLECTION_INIT_SCRIPT = 2240;

	private static final String COLLECTION_SYNC_LABEL = "Show Me Your ClogHole";
	/** The button's hover and click option: says what it does; the label keeps its joke. */
	private static final String COLLECTION_SYNC_ACTION = "Sync your collection log with SagaSeer";

	private static final int[] COLLECTION_BUTTON_SPRITES_INACTIVE = {
			SpriteID.TRADEBACKING,
			SpriteID.V2StoneButtonOut.A_TOP_LEFT,
			SpriteID.V2StoneButtonOut.A_TOP_RIGHT,
			SpriteID.V2StoneButtonOut.A_BOTTOM_LEFT,
			SpriteID.V2StoneButtonOut.A_BOTTOM_RIGHT,
			SpriteID.V2StoneButtonOut.A_MAP_EDGE_LEFT,
			SpriteID.V2StoneButtonOut.A_MAP_EDGE_TOP,
			SpriteID.V2StoneButtonOut.A_MAP_EDGE_RIGHT,
			SpriteID.V2StoneButtonOut.A_MAP_EDGE_BOTTOM
	};

	private static final int[] COLLECTION_BUTTON_SPRITES_ACTIVE = {
			SpriteID.TRADEBACKING_DARK,
			SpriteID.V2StoneButtonIn.A_TOP_LEFT,
			SpriteID.V2StoneButtonIn.A_TOP_RIGHT,
			SpriteID.V2StoneButtonIn.A_BOTTOM_LEFT,
			SpriteID.V2StoneButtonIn.A_BOTTOM_RIGHT,
			SpriteID.V2StoneButtonIn.A_LEFT,
			SpriteID.V2StoneButtonIn.A_TOP,
			SpriteID.V2StoneButtonIn.A_RIGHT,
			SpriteID.V2StoneButtonIn.A_BOTTOM
	};

	private static final int COLLECTION_BUTTON_FONT_INACTIVE = 0xD6D6D6;
	private static final int COLLECTION_BUTTON_FONT_ACTIVE = 0xFFFFFF;

	private static final int COLLECTION_CLOSE_BUTTON_OFFSET = 28;
	private static final int COLLECTION_BUTTON_OFFSET = COLLECTION_CLOSE_BUTTON_OFFSET + 5;

	private static final int COLLECTION_BUTTON_WIDTH = 132;

	private Widget shortenedTopbar;
	private int shortenedTopbarWidth;

	private final List<Widget> collectionSyncWidgets = new ArrayList<>();

	@Override
	protected void startUp() throws Exception
	{
		modeGuardActive = client != null;
		snapshotSyncPaused = false;
		worldScope = null;
		worldAccount = null;
		lastFixedScope = null;
		modeReadyTick = -1;
		if (httpClient != null && gson != null)
		{
			Filepath folder = getPluginDirectory();
			changeAwareBridge = new ChangeAwareEngineBridge(httpClient, gson,
				SagaSeerServiceOrigins.PLATFORM, folder.joinSegment("journal"));
			changeAwareBridge.observeDenials(this::handleCaptureDenial);
			PrelinkBuffer kept = new PrelinkBuffer(folder.joinSegment("prelink"),
				gson, Instant::now);
			changeAwareBridge.prelink(kept);
			changeAwareBridge.onStatus(() -> { panelPull.want(); showSidebar(); });
			// The account turned a data type off: drop what is held for it.
			changeAwareBridge.onPolicy(() -> { if (clientThread != null) clientThread.invoke(this::dropWhatIsOff); });
			localTrail = new LocalTrail(kept, folder.joinSegment("recent"), gson);
			localTrail.onView(this::showSidebar);
		}
		refreshOff();
		resetCollectors();
		if (client != null && client.getGameState() == GameState.LOGGED_IN)
			modeReadyTick = currentGameTick() + MODE_SETTLE_TICKS;
		if (configManager != null && gson != null && httpClient != null && clientToolbar != null && clientThread != null)
		{
			linkIo = Executors.newSingleThreadExecutor(task ->
			{
				Thread thread = new Thread(task, "sagaseer-link-open");
				thread.setDaemon(true);
				return thread;
			});
			int started = lifecycle;
			CompletableFuture.supplyAsync(() ->
			{
				try
				{
					return SagaSeerLink.open(httpClient, gson, getPluginDirectory(),
							SagaSeerServiceOrigins.PLATFORM, SagaSeerServiceOrigins.WEBSITE);
				}
				catch (IOException e)
				{
					log.warn("SagaSeer link storage unavailable; uploads stay off");
					return null;
				}
			}, linkIo).thenAccept(link ->
			{
				if (link == null) return;
				SwingUtilities.invokeLater(() ->
				{
					if (started != lifecycle) return;
					SagaSeerLinkPanel panel = new SagaSeerLinkPanel(link, () -> clientThread.invoke(() ->
					{
						snapshotSyncPaused = false;
						resetPanel();
						SagaSeerLinkPanel current = linkPanel;
						if (current != null) current.clearUploadError();
						fullResyncWanted = true;
						requestSnapshot("LINKED_FULL");
						showSidebar();
					}), new SagaSeerLinkPanel.Actions()
					{
						@Override
						public void togglePause()
						{
							configManager.setConfiguration(SagaSeerConfig.GROUP, "pauseUploads", !pausedByPlayer());
						}

						@Override
						public void clearLocalData()
						{
							LocalTrail trail = localTrail;
							if (trail != null) trail.clear();
						}
					}, itemManager);
					panel.start(clientToolbar);
					linkPanel = panel;
					// shutDown may run while this builds the panel: then it is removed again.
					if (started != lifecycle)
					{
						if (linkPanel == panel) linkPanel = null;
						panel.stop();
						return;
					}
					showSidebar();
					LocalTrail trail = localTrail;
					if (trail != null) trail.refresh();
				});
			});
		}
		log.info("SagaSeer started");
	}

	@Override
	protected void shutDown() throws Exception
	{
		modeGuardActive = false;
		worldScope = null;
		worldAccount = null;
		modeReadyTick = -1;
		resetCollectors();
		if (changeAwareBridge != null) changeAwareBridge.shutdown();
		LocalTrail trail = localTrail;
		localTrail = null;
		if (trail != null) trail.shutdown();
		changeAwareBridge = null;
		lifecycle++;
		SagaSeerLinkPanel panel = linkPanel;
		linkPanel = null;
		if (panel != null) SwingUtilities.invokeLater(panel::stop);
		ExecutorService openIo = linkIo;
		linkIo = null;
		if (openIo != null) openIo.shutdownNow();
		if (clientThread != null) clientThread.invoke(this::removeCollectionSyncButton);
		log.debug("SagaSeer stopped!");
	}

	private void resetCollectors()
	{
		log.debug("WORLD MODE resetting collectors");
		changeAwareInventorySource.reset();
		changeAwareEquipmentSource.reset();
		raidRewards.reset();
		gamePatches.reset();
		bossRecordChatCapture.reset();
		clueChat.reset();
		checkChat.reset();
		tears.reset();
		house.reset();
		grandExchangeHistory.reset();
		playTimeState.reset();
		logAnnouncements.reset();
		deathDetails.reset();
		deathCause.reset();
		observedCounts.reset();
		hunterRumourState.reset();
		stashUnits.reset();
		stashBoard.reset();
		storageLocations.reset();
		collectionLogState.reset();
		deathItems.reset();
		killLog.reset();
		gameTriggers.sessionEnded();
		stashWantedAt = -1;
		slayerKillsLeft = -1;
		snapshotScheduler.clear();
		entryObservationSettleGuard.reset();
		lootingBagWidgetWasVisible = false;
		grandExchangeSent = false;
		fullResyncWanted = false;
		lastLmsArea = false;
		lastArenaArea = false;
	}

	String currentCollectionLogName()
	{
		return client.getLocalPlayer() == null
				? null : client.getLocalPlayer().getName();
	}

	boolean collectionLogIsLoggedIn()
	{
		return client.getGameState() == GameState.LOGGED_IN;
	}

	Instant hunterRumourCallbackTime()
	{
		return Instant.now();
	}

	HunterRumourState.Total currentHunterRumourObservation()
	{
		return hunterRumourState.snapshot();
	}

	HunterRumourDialogue.Rumour hunterRumourCurrent()
	{
		return hunterRumourState.currentRumour();
	}

	boolean observeCollectionLogIdentity(String account, boolean loggedIn)
	{
		return collectionLogState.observeIdentity(account, loggedIn);
	}

	private CollectionLogLayout collectionLogLayout()
	{
		if (collectionLogLayout == null)
		{
			collectionLogLayout = CollectionLogLayout.read(client);
			if (collectionLogLayout == null) log.debug("Collection Log layout unreadable");
		}
		return collectionLogLayout;
	}

	List<CollectionLogRows.Change> takeCollectionLogChanges(String account, boolean loggedIn)
	{
		return collectionLogState.takeChanges(account, loggedIn);
	}

	boolean recordCollectionLogChange(String account, boolean loggedIn,
			CollectionLogRows.Change change)
	{
		if (!reads("state.collectionLog")) return false;
		return collectionLogState.addChange(account, loggedIn, change);
	}

	void startCollectionLogNotification(String account, boolean loggedIn)
	{
		collectionLogState.startNotification(account, loggedIn);
	}

	boolean consumeCollectionLogNotification(String account, boolean loggedIn)
	{
		return collectionLogState.consumeNotificationDelay(account, loggedIn);
	}

	boolean beginCollectionLogInstant(String account, boolean loggedIn)
	{
		if (!reads("state.collectionLog")) return false;
		return collectionLogState.beginInstant(account, loggedIn);
	}

	boolean acceptCollectionLogInstant(String account, boolean loggedIn,
			int itemId, int quantity, int tick)
	{
		if (!reads("state.collectionLog")) return false;
		return collectionLogState.acceptInstant(account, loggedIn, itemId, quantity, tick);
	}

	boolean completeCollectionLogInstant(String account, boolean loggedIn,
			int currentTick, Instant at, CollectionLogLayout layout)
	{
		return collectionLogState.completeInstant(account, loggedIn, currentTick, at, layout);
	}

	/**
	 * Loot Tracker events (a kill with loot, a pickpocket, an opened reward), counted by name for log items whose source has no
	 * game counter. Needs the Loot Tracker plugin; another player's loot is never counted.
	 */
	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		String kind = String.valueOf(event.getType());
		// A Seed pack event uses up its Take click even when not counted, and reads the inventory only when it is.
		boolean counting = reads("state.dropAttempts") && collectionLogIsLoggedIn();
		int amount = observedCounts.lootAmount(kind, event.getName(), event.getAmount(), counting, this::seedPacksHeld);
		if (amount == 0) return;
		observedCounts.loot(currentCollectionLogName(), kind, event.getName(), amount, Instant.now());
	}

	private int seedPacksHeld()
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		return inventory == null ? -1 : inventory.count(ItemID.SEEDBOX);
	}

	private void noteSlayerTask(String account)
	{
		noteSlayerTask(account, GameSlayer.read(client, Instant::now));
	}

	private void noteSlayerTask(String account, ChangeAwareSourceCapture.Observation read)
	{
		observedCounts.task(account, read.observed() ? LocalTrail.first(read.value) : null, Instant.now());
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		GameState newState = gameStateChanged.getGameState();
		if (client != null)
		{
			deathItems.gameState(newState == GameState.LOADING, newState == GameState.LOGGED_IN, currentGameTick());
		}
		if (newState == GameState.LOGIN_SCREEN) deathItems.logout();
		house.onGameStateChanged(newState);
		// Only a logout, hop or lost connection resets upload readiness, never a loading screen.
		if (sessionEnds(newState))
		{
			worldScope = null;
			worldAccount = null;
			modeReadyTick = -1;
			changeAwareInventorySource.reset();
			changeAwareEquipmentSource.reset();
			storageLocations.endVisits();
			grandExchangeSent = false;
		}
		if (sessionEnds(newState) && changeAwareBridge != null)
			changeAwareBridge.fenceSession();
		// The held raid row survives loading screens; entering or leaving an instance only re-arms the chest latch.
		if (sessionEnds(newState))
		{
			raidRewards.reset();
			gamePatches.reset();
		}
		if (newState == GameState.LOADING)
		{
			boolean instanced = inInstancedRegion();
			if (instanced != lastLoadingIntoInstance)
			{
				lastLoadingIntoInstance = instanced;
				raidRewards.rearm();
			}
		}
		// Kill counts not yet uploaded survive loading screens; only a real session end drops them.
		if (sessionEnds(newState))
		{
			resetPanel();
			slayerKillsLeft = -1;
			clanSettledTick = -1L;
			clanLeft = false;
			bossRecordChatCapture.reset();
			clueChat.reset();
			checkChat.reset();
			tears.reset();
			house.reset();
			grandExchangeHistory.reset();
			hunterRumourState.reset();
			stashUnits.reset();
			stashBoard.reset();
			killLog.reset();
			// A death's cause is read when it happens, so this only forgets the target remembered from the session that ended.
			deathCause.reset();
			observedCounts.resetXpBaseline();
			observedCounts.forgetSeedPackClick();
			stashWantedAt = -1;
			playTimeState.reset();
			gameTriggers.sessionEnded();
		}
		// Collection Log changes waiting to upload survive loading screens.
		if (newState == GameState.LOGGED_IN || sessionEnds(newState))
			observeCollectionLogIdentity(currentCollectionLogName(), newState == GameState.LOGGED_IN);
		if (newState == GameState.LOGGED_IN && !stashUnits.hasResult() && stashWantedAt < 0)
			stashWantedAt = currentGameTick() + STASH_LOGIN_DELAY_TICKS;

		/*
		 * Keep why the player is entering the game across LOADING: a login is LOGIN_SCREEN/LOGGING_IN -> LOADING -> LOGGED_IN, a
		 * hop is HOPPING -> LOADING -> LOGGED_IN, and teleports can be LOGGED_IN -> LOADING -> LOGGED_IN, so LOADING alone never
		 * arms an entry snapshot.
		 */
		if (newState == GameState.HOPPING)
		{
			entryIntentReason = "WORLD_HOP";
		}
		else if (newState == GameState.LOGIN_SCREEN || newState == GameState.LOGGING_IN)
		{
			if (!"WORLD_HOP".equals(entryIntentReason))
			{
				entryIntentReason = "LOGIN";
			}
		}
		else if (newState == GameState.LOGGED_IN && entryIntentReason != null)
		{
			pendingEntrySnapshotReason = entryIntentReason;
			entryObservationSettleGuard.begin(currentGameTick(), ENTRY_SNAPSHOT_SETTLE_TICKS);
			modeReadyTick = currentGameTick() + MODE_SETTLE_TICKS;
			entryIntentReason = null;
			clanSettledTick = currentGameTick() + CLAN_SETTLE_TICKS;
			clanLeft = false;
		}

	}

	private void fixWorldScopeIfSettled()
	{
		if (client == null || client.getGameState() != GameState.LOGGED_IN || !hasLocalPlayer()
			|| modeReadyTick < 0 || currentGameTick() < modeReadyTick
			|| worldScope != null && (worldScope.mode != WorldMode.Mode.LEAGUES || worldScope.leagueType != null)) return;
		EnumSet<WorldType> types = client.getWorldType();
		WorldMode.Mode mode = WorldMode.of(types);
		int league = mode == WorldMode.Mode.LEAGUES ? client.getVarbitValue(VarbitID.LEAGUE_TYPE) : 0;
		WorldMode.Scope fixed = new WorldMode.Scope(mode, league == 0 ? null : league);
		applyWorldScope(fixed);
		worldAccount = client.getLocalPlayer().getName();
		showSidebar();
	}

	void applyWorldScope(WorldMode.Scope fixed)
	{
		if (fixed.changedFrom(lastFixedScope))
		{
			log.debug("WORLD MODE scope changed {} -> {}", lastFixedScope.mode, fixed.mode);
			resetCollectors();
			if (changeAwareBridge != null)
			{
				changeAwareBridge.fenceSession();
				changeAwareBridge.clearWorldRefusal();
			}
			resetPanel();
			if (localTrail != null) localTrail.refresh();
		}
		worldScope = fixed;
		lastFixedScope = fixed;
		if (!fixed.mode.recorded()) dropWhatIsOff();
	}

	/** Only an active LMS game (its in-game varbit) or PvP Arena fight (its HUD) loans gear and stats. */
	boolean inLoanedArea()
	{
		if (client == null || client.getGameState() != GameState.LOGGED_IN || worldScope == null) return false;
		boolean lms = client.getVarbitValue(LMS_IN_GAME_VARBIT) == 1;
		Widget arena = client.getWidget(PVP_ARENA_FIGHT_INTERFACE);
		boolean pvpArena = arena != null && !arena.isHidden();
		if (lms != lastLmsArea || pvpArena != lastArenaArea)
		{
			// Both edges: loaned stats and gear never become a level, XP hit or death-items baseline, nor a death's opponent.
			gameTriggers.drop(false, Set.of("state.skills"));
			observedCounts.resetXpBaseline();
			deathItems.reset();
			deathCause.reset();
			if (lms || pvpArena)
			{
				changeAwareInventorySource.reset();
				changeAwareEquipmentSource.reset();
			}
			else if (client.getLocalPlayer() != null)
			{
				String name = client.getLocalPlayer().getName();
				changeAwareInventorySource.containerEvent(name);
				changeAwareEquipmentSource.containerEvent(name);
			}
			lastLmsArea = lms;
			lastArenaArea = pvpArena;
			showSidebar();
		}
		return lms || pvpArena;
	}
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		if (reads("state.itemCharges"))
		{
			int checkedId = "Check".equals(event.getMenuOption()) ? clickedItemId(event.getItemId(), event.getWidget()) : -1;
			checkChat.clicked(event.getMenuOption(), checkedId, checkedId > 0 ? itemName(checkedId) : null, Instant.now());
		}
		if (reads("state.house")) house.onMenuOptionClicked(event, client);
		if (reads("state.dropAttempts") && event.getItemId() == ItemID.SEEDBOX
			&& ("Take".equals(event.getMenuOption()) || "Take-all".equals(event.getMenuOption())))
		{
			observedCounts.seedPackClick(seedPacksHeld());
		}
	}

	@Subscribe
	public void onGameTick(GameTick gameTick)
	{
		fixWorldScopeIfSettled();
		inLoanedArea();
		askForPanel();
		if (modeGuardActive && (worldScope == null || !worldScope.mode.recorded())) return;
		Player deathPlayer = client == null ? null : client.getLocalPlayer();
		if (deathPlayer != null && deathPlayer.getName() != null)
		{
			DeathItems.Result done = deathItems.tick(deathPlayer.getName(), currentGameTick(),
				client.getGameState() == GameState.LOGGED_IN, boostedHitpointsOrUnknown(),
				reads("state.deaths") && reads("state.inventory") && reads("state.equipment"));
			if (done != null && done.change != null && deathDetails.items(done.account, done.at, done.change, Instant.now()))
				requestSnapshot("DEATH_ITEMS");
		}
		if (bossRecordChatCapture.hasEvidence() && collectionLogIsLoggedIn())
		{
			bossRecordChatCapture.observeIdentity(bossRecordCaptureSelector(),
					bossRecordCaptureAllowed());
		}
		if (collectionLogIsLoggedIn())
		{
			if (reads("state.house") && house.tick(client, Instant.now())) requestSnapshot("HOUSE");
			if (reads("state.grandExchangeHistory") && grandExchangeHistory.tick(client, this::itemName, Instant.now()))
				requestSnapshot("GRAND_EXCHANGE_HISTORY");
			observeCollectionLogIdentity(currentCollectionLogName(), true);
		}
		if (!hasLocalPlayer())
		{
			return;
		}
		if (storageLocations.bankSettled(Instant.now())) finishVisit(StorageLocations.Location.BANK);
		if (collectionLogIsLoggedIn() && entryObservationSettleGuard.remainingTicks(currentGameTick()) == 0)
		{
			if (reads("state.combatAchievements")) gameTriggers.taskWordBaseline(client::getVarpValue);
			if (reads("state.raidRewards")) raidRewards.counterBaseline(client::getVarpValue);
		}
		if (raidRewards.settle(currentGameTick(), Instant.now(), this::itemName))
		{
			requestSnapshot("RAID_REWARD");
		}
		if (stashTick())
		{
			requestSnapshot("STASH");
		}
		if (stashBoardTick())
		{
			requestSnapshot("STASH_BOARD");
		}
		if (killLogTick()) requestSnapshot("KILL_LOG");
		if (farmingTick())
		{
			requestSnapshot("FARMING_PATCH");
		}

		if (reads("state.storage"))
		{
			observeLootingBagWidgetIfOpened();
			observeCostumeRoomIfOpen();
		}

		/*
		 * Triggers coalesce into one pending upload, sent once its settle deadline and the one-second interval have passed, built
		 * fresh at send time.
		 */
		schedulePendingEntrySnapshot();
		entryObservationSettleGuard.clearIfSettled(client.getTickCount());
		flushPendingSnapshotIfReady();

		/* A full log sync is complete two ticks after the last streamed item, as RuneProfile does. */
		if (
				collectionLogState.instantRetrieval &&
						collectionLogState.instantLastTransmitTick != -1 &&
						collectionLogState.instantLastTransmitTick + 2 <
								client.getTickCount()
		)
		{
			log.debug(
					"CLOG INSTANT COMPLETE: uniqueObtainedItems={} lastTransmitTick={} currentTick={}",
					collectionLogState.instantItems.size(),
					collectionLogState.instantLastTransmitTick,
					client.getTickCount()
			);
			if (!completeCollectionLogInstant(
					currentCollectionLogName(),
					collectionLogIsLoggedIn(),
					client.getTickCount(), Instant.now(), collectionLogLayout()))
			{
				return;
			}

			setCollectionSyncButtonLabel(COLLECTION_SYNC_LABEL);

			requestSnapshot("CLOG_MANUAL");
		}

		if (heartbeatDue(snapshotScheduler.hasPending(), System.currentTimeMillis(), lastUploadTime))
		{
			requestSnapshot("HEARTBEAT");
		}
	}

	static final long HEARTBEAT_INTERVAL_MS = 15 * 60 * 1000L;

	static boolean fullOnEntry(String entryReason)
	{
		return "LOGIN".equals(entryReason);
	}

	static boolean confirmsUnchanged(String reason)
	{
		return "LOGIN".equals(reason) || "HEARTBEAT".equals(reason);
	}

	static boolean sessionEnds(GameState state)
	{
		return state == GameState.LOGIN_SCREEN || state == GameState.HOPPING ||
				state == GameState.CONNECTION_LOST;
	}

	static boolean heartbeatDue(boolean uploadPending, long nowMillis, long lastUploadMillis)
	{
		return !uploadPending && nowMillis - lastUploadMillis >= HEARTBEAT_INTERVAL_MS;
	}

	static boolean isIndividualDiaryTaskMessage(String lowerMessage)
	{
			return lowerMessage.startsWith("well done! you have completed ") &&
							lowerMessage.contains(" task in the ") &&
							lowerMessage.endsWith(" area. your achievement diary has been updated.");
	}

	/*
	 * Chat triggers: only the game's own messages to the player (GAMEMESSAGE and MESBOX, and Guild Hunters' DIALOG lines), never
	 * player chat. The text itself is not uploaded.
	 */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		Instant callbackAt = hunterRumourCallbackTime();
		if (!collectionLogIsLoggedIn())
		{
			return;
		}

		if (reads("state.raidRewards") && raidRewards.chat(event.getType(), event.getMessage(), currentCollectionLogName(), callbackAt))
		{
			requestSnapshot("RAID_REWARD");
		}

		if ((event.getType() == ChatMessageType.GAMEMESSAGE || event.getType() == ChatMessageType.SPAM)
				&& reads("state.consumables") && !inLoanedArea())
		{
			String line = stripTags(event.getMessage()).trim();
			Matcher eaten = EAT_LINE.matcher(line);
			Matcher drunk = DRINK_LINE.matcher(line);
			if (eaten.matches()) observedCounts.consumed(currentCollectionLogName(), "EAT", eaten.group(1), callbackAt);
			else if (drunk.matches()) observedCounts.consumed(currentCollectionLogName(), "DRINK", drunk.group(1), callbackAt);
		}

		// The superior line (as RuneLite's Slayer plugin reads it), counted for the last-read task.
		if ((event.getType() == ChatMessageType.GAMEMESSAGE || event.getType() == ChatMessageType.SPAM)
				&& ObservedCounts.isSuperiorMessage(event.getMessage()) && reads("state.slayerSuperiors"))
		{
			String account = currentCollectionLogName();
			noteSlayerTask(account);
			observedCounts.superior(account, Instant.now());
			requestSnapshot("SLAYER_SUPERIOR");
		}

		// The Check line of an item whose counter stays 0 is its charges.
		if (reads("state.itemCharges") && checkChat.accept(event.getType(), stripTags(event.getMessage()), Instant.now()))
		{
			requestSnapshot("ITEM_CHARGES_CHECK");
		}

		// An empty seed box sends no container, so its Check line is the read. Before the type filter: it may arrive as SPAM.
		if (StorageLocations.emptySeedBox(event.getType(), stripTags(event.getMessage()).trim()) && reads("state.storage")
				&& storageLocations.observe(client.getLocalPlayer().getName(), StorageLocations.Location.SEED_BOX, new Item[0],
						this::itemName, Instant.now()))
		{
			requestSnapshot("STORAGE_SEED_BOX");
		}

		// A Guild Hunter's DIALOG line "Speaker|text", which matches the dialogue box, chat filter or not. Other speakers are
		// ignored.
		if (event.getType() == ChatMessageType.DIALOG && reads("state.hunterRumours"))
		{
			String line = event.getMessage();
			int bar = line == null ? -1 : line.indexOf('|');
			if (bar > 0 && hunterRumourState.observeDialogue(line.substring(0, bar), line.substring(bar + 1), hunterRumourCallbackTime()))
				requestSnapshot("HUNTER_RUMOUR");
		}

		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.MESBOX)
		{
			return;
		}
		if (event.getType() == ChatMessageType.GAMEMESSAGE && reads("state.bosses"))
		{
			if (bossRecordChatCapture.accept(bossRecordCaptureSelector(),
					collectionLogIsLoggedIn() && bossRecordCaptureAllowed(), currentGameTick(),
					bossRecordObservedAt(), event.getMessage()))
				requestSnapshot("BOSS_RECORD", BOSS_RECORD_SETTLE_TICKS);
		}

		String message = stripTags(event.getMessage()).trim();

		String lowerMessage = message.toLowerCase();

		/*
		 * A full diary tier: the MESBOX line "You have completed all of the easy tasks in the ... area". Single tasks are
		 * GAMEMESSAGE and don't match here.
		 */
		if (
				event.getType() == ChatMessageType.MESBOX &&
						lowerMessage.contains("you have completed all of the ") &&
						lowerMessage.contains(" tasks in the ") &&
						lowerMessage.contains(" area.")
		)
		{
			log.debug("PUSH TRIGGER: Achievement Diary completed");

			requestSnapshot("DIARY_COMPLETE");

			return;
		}

		if (event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}

		if (reads("state.activities") && clueChat.accept(message))
		{
			requestSnapshot("CLUE_COMPLETED");
			return;
		}

		if (reads("state.hunterRumours") && hunterRumourState.observeTotal(event.getMessage(), callbackAt))
		{
			requestSnapshot("HUNTER_RUMOUR");
			return;
		}

		if (isIndividualDiaryTaskMessage(lowerMessage))
		{
				log.debug("PUSH TRIGGER: Achievement Diary task completed");

				requestSnapshot("DIARY_TASK");

				return;
		}

		if (message.contains("Congratulations! Quest complete!") || lowerMessage.contains("you've completed a quest"))
		{
			log.debug("PUSH TRIGGER: quest completed");

			requestSnapshot("QUEST_COMPLETED");

			return;
		}

		final String collectionPrefix = "New item added to your collection log:";

		if (message.startsWith(collectionPrefix))
		{
			String itemName = message.substring(collectionPrefix.length()).trim();
			observeCollectionLogDirect(itemName);

			log.debug("PUSH TRIGGER: collection log item gained '{}'", itemName);

			requestSnapshot(itemName.isEmpty() ? "COLLECTION_LOG_ITEM" : "COLLECTION_LOG_ITEM:" + itemName);
		}
	}

	private void observeCollectionLogDirect(String itemName)
	{
		// The drop's moment, so the server can date the item before the log's first sync.
		if (reads("state.logAnnouncements")) logAnnouncements.announced(currentCollectionLogName(), itemName, Instant.now(),
			collectionLogLayout());
		recordCollectionLogChange(currentCollectionLogName(), collectionLogIsLoggedIn(),
				new CollectionLogRows.NewItem(Instant.now(), itemName));
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		/*
		 * Popup fallback, as RuneLite's Screenshot plugin reads the notification scripts: only in popup-only mode
		 * (COLLECTION_LOG_NOTIFICATION == 2), so the chat path never counts a drop twice.
		 */
		if (event.getScriptId() == ScriptID.NOTIFICATION_START)
		{
			startCollectionLogNotification(currentCollectionLogName(), collectionLogIsLoggedIn());
			return;
		}

		if (event.getScriptId() == ScriptID.NOTIFICATION_DELAY)
		{
			if (!consumeCollectionLogNotification(currentCollectionLogName(), collectionLogIsLoggedIn()))
			{
				return;
			}

			if (client.getVarbitValue(VarbitID.OPTION_COLLECTION_NEW_ITEM) != 2)
			{
				return;
			}

			String topText = stripTags(client.getVarcStrValue(VarClientID.NOTIFICATION_TITLE)).trim();

			String bottomText = stripTags(client.getVarcStrValue(VarClientID.NOTIFICATION_MAIN)).trim();

			if (!"Collection log".equalsIgnoreCase(topText))
			{
				return;
			}

			final String popupPrefix = "New item:";

			if (!bottomText.regionMatches(true, 0, popupPrefix, 0, popupPrefix.length()))
			{
				return;
			}

			String itemName = bottomText.substring(popupPrefix.length()).trim();
			observeCollectionLogDirect(itemName);

			log.debug("PUSH TRIGGER: collection log popup item gained '{}'", itemName);

			requestSnapshot(itemName.isEmpty() ? "COLLECTION_LOG_ITEM" : "COLLECTION_LOG_ITEM:" + itemName);

			return;
		}

		if (event.getScriptId() == COLLECTION_DELAYED_TRANSMIT)
		{
			if (!observeCollectionLogIdentity(currentCollectionLogName(), collectionLogIsLoggedIn()))
			{
				return;
			}
			/* Other plugins can trigger the same transmit: only events during our own sync count. */
			if (!collectionLogState.instantRetrieval)
			{
				return;
			}

			Object[] args = event.getScriptEvent().getArguments();

			if (args != null && args.length >= 3 && args[1] instanceof Integer && args[2] instanceof Integer)
			{
				int itemId = (Integer) args[1];
				int quantity = (Integer) args[2];

				acceptCollectionLogInstant(
						currentCollectionLogName(), collectionLogIsLoggedIn(),
						itemId, quantity, client.getTickCount());
			}

					return;
			}
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		/*
		 * The log opened: add the button (opening never syncs by itself). The old one goes first: runScript(2240) fires the setup
		 * script again.
		 */
		if (event.getScriptId() == COLLECTION_LOG_SETUP)
		{
			if (!observeCollectionLogIdentity(currentCollectionLogName(), collectionLogIsLoggedIn()))
			{
				return;
			}
			removeCollectionSyncButton();
			addCollectionSyncButton();
			return;
		}

		if (event.getScriptId() != ScriptID.COLLECTION_DRAW_LIST)
		{
			return;
		}
		if (!observeCollectionLogIdentity(currentCollectionLogName(), collectionLogIsLoggedIn()))
		{
			return;
		}

		long now = System.currentTimeMillis();

		if (now - collectionLogState.lastCaptureTime < 500)
		{
			return;
		}

		collectionLogState.lastCaptureTime = now;

		Widget header = client.getWidget(InterfaceID.Collection.HEADER_TEXT);

		Widget itemsContainer = client.getWidget(InterfaceID.Collection.ITEMS_CONTENTS);

		if (
				header == null ||
						header.getChildren() == null ||
						header.getChildren().length == 0 ||
						header.getChild(0) == null ||
						itemsContainer == null ||
						itemsContainer.getChildren() == null
		)
		{
			log.debug("CLOG CAPTURE: required widgets not ready");
			return;
		}

		String pageName = stripTags(header.getChild(0).getText()).trim();

		if (pageName.isEmpty())
		{
			log.debug("CLOG CAPTURE: page name was empty");
			return;
		}

		List<CollectionLogRows.ItemQuantity> pageItems = new ArrayList<>();
		for (Widget child : itemsContainer.getChildren())
		{
			if (child == null || child.isHidden() || child.getItemId() <= 0)
			{
				continue;
			}
			String itemName = logItemName(child.getName());
			if (itemName.isEmpty())
			{
				itemName = itemName(child.getItemId());
			}
			boolean obtained = child.getOpacity() == 0;
			pageItems.add(new CollectionLogRows.ItemQuantity(child.getItemId(), itemName,
					obtained ? Math.max(1, child.getItemQuantity()) : 0));
		}

		List<String> headerLines = new ArrayList<>();
		for (int i = 1; i < header.getChildren().length; i++)
		{
			Widget line = header.getChild(i);
			if (line != null && line.getText() != null)
			{
				headerLines.add(stripTags(line.getText()).trim());
			}
		}

		if (!recordCollectionLogChange(
				currentCollectionLogName(),
				collectionLogIsLoggedIn(),
				new CollectionLogRows.PageOpen(Instant.now(), pageName, pageItems,
						CollectionLogRows.killCounts(headerLines), CollectionLogRows.bests(headerLines))))
		{
			return;
		}

		log.debug("CLOG CAPTURE: page opened, {} items", pageItems.size());
	}

	private void startInstantCollectionLogSync()
	{
		Widget searchButton = client.getWidget(InterfaceID.Collection.SEARCH_TOGGLE);

		if (searchButton == null || searchButton.isHidden())
		{
			return;
		}

		if (!beginCollectionLogInstant(currentCollectionLogName(), collectionLogIsLoggedIn()))
		{
			return;
		}

		setCollectionSyncButtonLabel("Syncing...");

		// The plugin's only game action: one Search click, only because the player pressed the sync button. No timer, retry or
		// loop repeats it.
		client.menuAction(-1, InterfaceID.Collection.SEARCH_TOGGLE, MenuAction.CC_OP, 1, -1, "Search", null);

		client.runScript(COLLECTION_INIT_SCRIPT);
	}

	private void addCollectionSyncButton()
	{
		Widget parent = client.getWidget(InterfaceID.Collection.UNIVERSE);

		Widget searchButton = client.getWidget(InterfaceID.Collection.SEARCH_TOGGLE);

		Widget collectionLogContainer = client.getWidget(InterfaceID.Collection.INFINITY);

		Widget[] containerChildren;
		Widget draggableTopbar;

		if (
				parent == null ||
						searchButton == null ||
						collectionLogContainer == null ||
						(containerChildren = collectionLogContainer.getChildren()) == null ||
						containerChildren.length == 0 ||
						(draggableTopbar = containerChildren[0]) == null
		)
		{
			return;
		}

		final int w = COLLECTION_BUTTON_WIDTH;
		final int h = searchButton.getOriginalHeight();

		final int x = COLLECTION_BUTTON_OFFSET;
		final int y = searchButton.getOriginalY();
		final int corner = 9;

		// The nine-slice frame in creation order (later pieces draw on top): sprite index, width, height, x and y offsets.
		int[][] pieces = {
			{0, w, h, 0, 0},
			{1, corner, corner, w - corner, 0},
			{2, corner, corner, 0, 0},
			{3, corner, corner, w - corner, h - corner},
			{4, corner, corner, 0, h - corner},
			{5, corner, 4, w - corner, corner},
			{7, corner, 4, 0, corner},
			{6, w - corner * 2, corner, corner, 0},
			{8, w - corner * 2, corner, corner, h - corner}};
		final Widget[] spriteWidgets = new Widget[9];
		for (int[] piece : pieces)
		{
			Widget sprite = parent.createChild(-1, WidgetType.GRAPHIC)
				.setSpriteId(COLLECTION_BUTTON_SPRITES_INACTIVE[piece[0]])
				.setXPositionMode(WidgetPositionMode.ABSOLUTE_RIGHT)
				.setSize(piece[1], piece[2])
				.setPos(x + piece[3], y + piece[4]);
			if (piece[0] == 0) sprite.setYPositionMode(searchButton.getYPositionMode());
			spriteWidgets[piece[0]] = sprite;
		}

		for (Widget spriteWidget : spriteWidgets)
		{
			spriteWidget.revalidate();
			collectionSyncWidgets.add(spriteWidget);
		}

		final Widget text = parent.createChild(-1, WidgetType.TEXT)
			.setText(COLLECTION_SYNC_LABEL)
			.setTextColor(COLLECTION_BUTTON_FONT_INACTIVE)
			.setFontId(FontID.PLAIN_11)
			.setTextShadowed(true)
			.setXPositionMode(WidgetPositionMode.ABSOLUTE_RIGHT)
			.setXTextAlignment(WidgetTextAlignment.CENTER)
			.setYTextAlignment(WidgetTextAlignment.CENTER)
			.setPos(x, y)
			.setSize(w, h)
			.setYPositionMode(searchButton.getYPositionMode());

		text.revalidate();
		text.setHasListener(true);
		text.setOnMouseOverListener((JavaScriptCallback) ev ->
			paintCollectionSyncButton(spriteWidgets, text, COLLECTION_BUTTON_SPRITES_ACTIVE, COLLECTION_BUTTON_FONT_ACTIVE));
		text.setOnMouseLeaveListener((JavaScriptCallback) ev ->
			paintCollectionSyncButton(spriteWidgets, text, COLLECTION_BUTTON_SPRITES_INACTIVE, COLLECTION_BUTTON_FONT_INACTIVE));
		text.setAction(0, COLLECTION_SYNC_ACTION);
		text.setOnOpListener((JavaScriptCallback) ev -> startInstantCollectionLogSync());

		collectionSyncWidgets.add(text);

		/*
		 * As WikiSync does, shorten the draggable top bar so the button doesn't overlap it; removing the button gives the width
		 * back.
		 */
		shortenedTopbar = draggableTopbar;
		shortenedTopbarWidth = draggableTopbar.getOriginalWidth();
		draggableTopbar.setOriginalWidth(draggableTopbar.getOriginalWidth() - (w + (x - COLLECTION_CLOSE_BUTTON_OFFSET)));

		draggableTopbar.revalidate();
		parent.revalidate();
	}

	private static void paintCollectionSyncButton(Widget[] sprites, Widget text, int[] spriteIds, int color)
	{
		for (int i = 0; i < sprites.length; i++) sprites[i].setSpriteId(spriteIds[i]);
		text.setTextColor(color);
	}

	private void setCollectionSyncButtonLabel(String label)
	{
		for (Widget widget : collectionSyncWidgets)
		{
			if (widget != null && widget.getType() == WidgetType.TEXT)
			{
				widget.setText(label);
				widget.revalidate();
			}
		}
	}

	private void removeCollectionSyncButton()
	{
		for (Widget widget : collectionSyncWidgets)
		{
			if (widget != null)
			{
				widget.setOnOpListener((Object[]) null);
				widget.setOnMouseOverListener((Object[]) null);
				widget.setOnMouseLeaveListener((Object[]) null);
				widget.setHasListener(false);
				widget.setAction(0, null);
				widget.setHidden(true);
			}
		}

		collectionSyncWidgets.clear();
		Widget topbar = shortenedTopbar;
		shortenedTopbar = null;
		if (topbar != null)
		{
			topbar.setOriginalWidth(shortenedTopbarWidth);
			topbar.revalidate();
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (modeGuardActive && (worldScope == null || !worldScope.mode.recorded())) return;
		int containerId = event.getContainerId();
		if (containerId == InventoryID.INV || containerId == InventoryID.WORN)
		{
			Player owner = client.getLocalPlayer();
			if (owner != null && owner.getName() != null)
			{
				boolean allowed = reads("state.deaths") && reads("state.inventory") && reads("state.equipment") && !inLoanedArea();
				if (allowed)
				{
					ItemContainer changed = event.getItemContainer();
					deathItems.container(owner.getName(), currentGameTick(), containerId,
						changed == null ? null : changed.getItems());
				}
				else deathItems.reset();
			}
		}
		RaidRewards.Raid rewardRaid = RaidRewards.raidOfContainer(containerId);
		if (rewardRaid != null)
		{
			ItemContainer rewardContainer = event.getItemContainer();
			if (reads("state.raidRewards")) raidRewards.container(rewardRaid, rewardContainer == null ? null : rewardContainer.getItems());
			return;
		}
		if (client.getLocalPlayer() == null)
		{
			return;
		}

		if (containerId == InventoryID.WORN)
		{
			if (inLoanedArea()) return;
				changeAwareEquipmentSource.containerEvent(client.getLocalPlayer().getName());
				return;
		}

		if (containerId == InventoryID.INV)
		{
			if (inLoanedArea()) return;
			changeAwareInventorySource.containerEvent(client.getLocalPlayer().getName());
			return;
		}

		StorageLocations.Location storage = StorageLocations.Location.of(containerId);
		if (storage != null)
		{
			observeStorage(storage, event.getItemContainer());
		}
	}

	/**
	 * The player died: only the area's name is kept (the one exception to no location), the opponent read once, and the death
	 * uploaded at once. The game's death counter still decides the count.
	 */
	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		Player me = client.getLocalPlayer();
		if (me == null || event.getActor() != me || me.getName() == null || !reads("state.deaths") || inLoanedArea()) return;
		int deathTick = currentGameTick();
		if (deathItems.seenDeath(me.getName(), deathTick)) return;
		int region = WorldPoint.fromLocalInstance(client, me.getLocalLocation()).getRegionID();
		String area = DeathDetails.areaOf(region, client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1);
		String at = deathDetails.died(me.getName(), Instant.now(), area, deathOpponent(me.getInteracting()));
		deathItems.died(me.getName(), at, deathTick, reads("state.inventory") && reads("state.equipment"));
		requestSnapshot("DEATH");
	}

	/** The player's NPC target now, else the one they left in the last minute. */
	String deathOpponent(Actor fighting)
	{
		return deathCause.opponent(client.getTickCount(), fighting instanceof NPC ? fighting.getName() : null);
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (reads("state.deaths") && !inLoanedArea() && event.getActor() == client.getLocalPlayer() && DeathCause.damage(event.getHitsplat().getHitsplatType(), event.getHitsplat().getAmount()))
			deathCause.hit(client.getTickCount());
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		if (reads("state.deaths") && !inLoanedArea() && event.getSource() == client.getLocalPlayer())
			deathCause.targeting(client.getTickCount(), event.getTarget() instanceof NPC ? event.getTarget().getName() : null);
	}

	/** A GE slot from the server: offers are readable from now on. The client's EMPTY events while logging in are skipped. */
	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		if (!GrandExchangeOffers.fromServer(client.getGameState())) return;
		grandExchangeSent = true;
		if (reads("state.grandExchange")) requestSnapshot("GRAND_EXCHANGE", UI_SNAPSHOT_SETTLE_TICKS);
	}

	@Subscribe
	public void onClanChannelChanged(ClanChannelChanged event)
	{
		if (event.isGuest()) return;
		if (event.getClanId() == ClanID.GROUP_IRONMAN) requestSnapshot("GIM_GROUP");
		if (event.getClanId() == ClanID.CLAN)
		{
			clanLeft = event.getClanChannel() == null && client.getGameState() == GameState.LOGGED_IN;
			requestSnapshot("CLAN");
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
			if (!hasLocalPlayer())
			{
					return;
			}
			if (event.getVarbitId() == VarbitID.TOG_TEARS_COLLECTED && reads("state.activities")
					&& tears.changed(event.getValue(), Instant.now(), client.getOverallExperience()))
			{
					requestSnapshot("TEARS_OF_GUTHIX");
			}
			if (GameTriggers.isTaskWord(event.getVarpId()) && reads("state.combatAchievements")
					&& gameTriggers.taskCompleted(event.getVarpId(), event.getValue()))
			{
					requestSnapshot("COMBAT_ACHIEVEMENT");
			}
			if (event.getVarbitId() == -1 && RaidRewards.isCounter(event.getVarpId()) && reads("state.raidRewards"))
			{
					raidRewards.counterChanged(event.getVarpId(), event.getValue());
			}
			if (AchievementDiaryState.isDiaryVarp(event.getVarpId()))
			{
					requestSnapshot("DIARY_WORD", Math.max(UI_SNAPSHOT_SETTLE_TICKS,
							entryObservationSettleGuard.remainingTicks(currentGameTick())));
			}

			boolean slayerChanged = GameSlayer.relevant(event);
			if (event.getVarpId() == VarPlayerID.SLAYER_COUNT)
			{
					slayerChanged |= GameSlayer.startsOrEndsTask(slayerKillsLeft, event.getValue());
					slayerKillsLeft = event.getValue();
					// The panel's kill count follows the client: a redraw, not an upload.
					showSidebar();
			}
			if (slayerChanged)
			{
					panelPull.want();
					requestSnapshot("SLAYER_STATE", Math.max(UI_SNAPSHOT_SETTLE_TICKS,
							entryObservationSettleGuard.remainingTicks(currentGameTick())));
			}
  }

	/**
	 * The costume room is one container (POH_COSTUMES), held while its screen is open, a row when it closes. Own house only (the
	 * deposit side panel is present): visitors can see other players' chests.
	 */
	private void observeCostumeRoomIfOpen()
	{
		Widget screen = client.getWidget(InterfaceID.PohCostumes.ITEMS);
		if (screen == null || screen.isHidden() || client.getWidget(InterfaceID.PohCostumesSide.ITEMS) == null) return;
		ItemContainer room = client.getItemContainer(StorageLocations.Location.COSTUME_ROOM_CONTAINER);
		if (room == null) room = client.getItemContainer(InventoryID.POH_COSTUMES);
		if (room == null) return;
		storageLocations.visitSeen(client.getLocalPlayer().getName(), StorageLocations.Location.HOUSE_COSTUME_ROOM, room.getItems(), Instant.now());
	}

	private void observeLootingBagWidgetIfOpened()
	{
		Widget bagItems = client.getWidget(InterfaceID.WildernessLootingbag.ITEMS);
		if (bagItems == null || bagItems.isHidden())
		{
			lootingBagWidgetWasVisible = false;
			return;
		}
		if (lootingBagWidgetWasVisible)
		{
			return;
		}
		Widget[] children = bagItems.getDynamicChildren();
		if (children == null || children.length == 0)
		{
			return;
		}
		String[] texts = new String[children.length];
		int[] itemIds = new int[children.length];
		for (int slot = 0; slot < children.length; slot++)
		{
			Widget child = children[slot];
			texts[slot] = child == null ? null : child.getText();
			itemIds[slot] = child == null ? -1 : child.getItemId();
		}
		// A bag with items is read from its container (516), an empty one only by the game's text.
		lootingBagWidgetWasVisible = true;
		if (!LootingBagWidgetState.saysEmpty(texts, itemIds))
		{
			return;
		}

		if (storageLocations.observe(client.getLocalPlayer().getName(),
				StorageLocations.Location.LOOTING_BAG, new Item[0], this::itemName, Instant.now()))
		{
			requestSnapshot("PORTABLE_LOOTING_BAG", UI_SNAPSHOT_SETTLE_TICKS);
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		RaidRewards.Raid rewardRaid = RaidRewards.raidOfInterface(event.getGroupId());
		if (rewardRaid != null && reads("state.raidRewards") && (rewardRaid != RaidRewards.Raid.TOB || inTobChestRegion()))
		{
			ItemContainer rewardContainer = client.getItemContainer(rewardRaid.containerId);
			raidRewards.opened(rewardRaid, currentGameTick(), raidModeVarbit(rewardRaid), raidPartySize(rewardRaid),
					rewardContainer == null ? null : rewardContainer.getItems());
		}
		if (event.getGroupId() == InterfaceID.HIDEY_HOLES) stashBoard.opened();
		if (event.getGroupId() == InterfaceID.KILL_LOG) killLog.opened();
		if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			if (stashWantedAt < 0 && !stashUnits.running()) stashWantedAt = currentGameTick();
			log.debug("Bank opened - scheduling a full re-sync after UI settle");
			fullResyncWanted = true;
			requestSnapshot("BANK_OPEN", UI_SNAPSHOT_SETTLE_TICKS);
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.HIDEY_HOLES) stashBoard.closed();
		if (event.getGroupId() == InterfaceID.KILL_LOG) killLog.closed();
		for (StorageLocations.Location visited : StorageLocations.Location.closedBy(event.getGroupId()))
		{
			finishVisit(visited);
		}
	}

	private int boostedHitpointsOrUnknown()
	{
		try { return client == null ? -1 : client.getBoostedSkillLevel(Skill.HITPOINTS); }
		catch (RuntimeException unavailable) { return -1; }
	}

	/* At most one upload starts per second; triggers in that window replace the pending reason rather than queue. */
	private void schedulePendingEntrySnapshot()
	{
			if (pendingEntrySnapshotReason == null)
			{
					return;
			}

			String snapshotReason = pendingEntrySnapshotReason;

			pendingEntrySnapshotReason = null;
			if (fullOnEntry(snapshotReason)) fullResyncWanted = true;

			log.debug("PUSH TRIGGER: {}", snapshotReason);

			requestSnapshot(snapshotReason, entryObservationSettleGuard.remainingTicks(client.getTickCount()));
	}

	private void requestSnapshot(String snapshotReason)
	{
			requestSnapshot(snapshotReason, 0);
	}

	private void requestSnapshot(String snapshotReason, int settleTicks)
	{
			if (snapshotReason == null || snapshotReason.isBlank() || !hasLocalPlayer())
			{
					return;
			}

			String previousReason = snapshotScheduler.getPendingReason();

			if (previousReason != null && !previousReason.equals(snapshotReason))
			{
					log.debug("PUSH SCHEDULER: coalescing pending '{}' with newer '{}'", previousReason, snapshotReason);
			}

			snapshotScheduler.schedule(snapshotReason, currentGameTick(), settleTicks);
	}

	private void flushPendingSnapshotIfReady()
	{
		String account = client.getLocalPlayer() == null ? null :
			client.getLocalPlayer().getName();
		// Linked: upload. Not linked: the local trail keeps changes. Paused: neither.
		boolean changeAwareAllowed = !uploadsPaused()
			&& ((changeAwareBridge != null && linkedCredential() != null) || localTrail != null);
		if (dispatchPendingSnapshotIfReady(
				snapshotScheduler,
				account != null && !account.isBlank(),
				() -> client.getTickCount(),
				System::currentTimeMillis,
				lastUploadTime,
				!changeAwareAllowed,
				snapshotReason ->
				{
					log.debug("PUSH SCHEDULER: sending pending '{}'", snapshotReason);
					sendSnapshot(snapshotReason);
				}))
		{
			lastUploadTime = System.currentTimeMillis();
		}
	}

	static boolean dispatchPendingSnapshotIfReady(
			DeferredSnapshotScheduler scheduler,
			boolean playerPresent,
			LongSupplier currentTick,
			LongSupplier nowMillis,
			long lastUploadMillis,
			boolean publicationBlocked,
			Consumer<String> sender)
	{
		if (publicationBlocked || !scheduler.hasPending() || !playerPresent)
		{
			return false;
		}

		long tick = currentTick.getAsLong();
		if (!scheduler.isDue(tick))
		{
			return false;
		}

		long now = nowMillis.getAsLong();
		if (lastUploadMillis != 0L && now - lastUploadMillis < MIN_UPLOAD_INTERVAL_MS)
		{
			return false;
		}

		String reason = scheduler.takeIfDue(tick);
		if (reason == null)
		{
			return false;
		}

		sender.accept(reason);
		return true;
	}

	private Integer raidModeVarbit(RaidRewards.Raid raid)
	{
		switch (raid)
		{
			case COX:
				return client.getVarbitValue(VarbitID.RAIDS_CHALLENGE_MODE);
			case TOA:
				return client.getVarbitValue(VarbitID.TOA_CLIENT_RAID_LEVEL);
			default:
				return null;
		}
	}

	private static final int[] TOB_PARTY_SLOTS = {VarbitID.TOB_CLIENT_P0, VarbitID.TOB_CLIENT_P1, VarbitID.TOB_CLIENT_P2,
		VarbitID.TOB_CLIENT_P3, VarbitID.TOB_CLIENT_P4};
	private static final int[] TOA_PARTY_SLOTS = {VarbitID.TOA_CLIENT_P0, VarbitID.TOA_CLIENT_P1, VarbitID.TOA_CLIENT_P2,
		VarbitID.TOA_CLIENT_P3, VarbitID.TOA_CLIENT_P4, VarbitID.TOA_CLIENT_P5, VarbitID.TOA_CLIENT_P6, VarbitID.TOA_CLIENT_P7};

	/** Party size, read as the chest opens; null for The Fractured Archive. */
	private Integer raidPartySize(RaidRewards.Raid raid)
	{
		switch (raid)
		{
			case COX:
				return client.getVarbitValue(VarbitID.RAIDS_CLIENT_PARTYSIZE);
			case TOB:
				return RaidRewards.filledSlots(TOB_PARTY_SLOTS, client::getVarbitValue);
			case TOA:
				return RaidRewards.filledSlots(TOA_PARTY_SLOTS, client::getVarbitValue);
			default:
				return null;
		}
	}

	/** The ToB chest interface is used outside the raid too; only the chest rooms count (Loot Tracker's regions). */
	private boolean inTobChestRegion()
	{
		if (client.getLocalPlayer() == null) return false;
		int region = WorldPoint.fromLocalInstance(client, client.getLocalPlayer().getLocalLocation()).getRegionID();
		return region == 12867 || region == 14642;
	}

	/**
	 * The clicked item: the click's id, else the slot's item (a worn item's Check carries none), else a child's; -1 when none.
	 */
	static int clickedItemId(int itemId, Widget widget)
	{
		if (itemId > 0) return itemId;
		if (widget == null) return -1;
		if (widget.getItemId() > 0) return widget.getItemId();
		Widget[] children = widget.getDynamicChildren();
		if (children != null) for (Widget child : children) if (child != null && child.getItemId() > 0) return child.getItemId();
		return -1;
	}

	private String itemName(int itemId)
	{
		return plainItemName(client.getItemDefinition(itemId).getName());
	}

	/** On a free world the client adds " (Members)" to members items' names; it isn't part of the name. */
	static String plainItemName(String name)
	{
		return name != null && name.endsWith(MEMBERS_MARK) ? name.substring(0, name.length() - MEMBERS_MARK.length()) : name;
	}

	static String logItemName(String widgetName)
	{
		return plainItemName(stripTags(widgetName).trim());
	}

	private Item[] runePouch()
	{
		EnumComposition runes = client.getEnum(EnumID.RUNEPOUCH_RUNE);
		return runes == null ? null : StorageLocations.runePouch(client::getVarbitValue, runes::getIntValue);
	}

	private void observeStorage(StorageLocations.Location location, ItemContainer container)
	{
		if (container == null || !reads("state.storage")) return;
		String character = client.getLocalPlayer().getName();
		if (location.visitInterface != 0)
			storageLocations.visitSeen(character, location, container.getItems(), Instant.now());
		else if (storageLocations.observeHold(character, location, container.getItems(), this::itemName,
			StorageLocations.boatName(location, client::getVarbitValue, client::getDBTableField), Instant.now()))
			requestSnapshot("STORAGE_" + location.name());
	}

	private void finishVisit(StorageLocations.Location location)
	{
		if (client.getLocalPlayer() == null) return;
		int[] tabs = null;
		if (location.isBank())
		{
			tabs = new int[StorageLocations.BANK_TABS];
			for (int tab = 0; tab < tabs.length; tab++)
				tabs[tab] = client.getVarbitValue(VarbitID.BANK_TAB_1 + tab);
		}
		if (storageLocations.visitClosed(client.getLocalPlayer().getName(), location, tabs, this::itemName,
			itemId -> client.getItemDefinition(itemId).getPlaceholderTemplateId() != -1))
			requestSnapshot(location.name() + "_CLOSE");
	}

	private void sendSnapshot(String snapshotReason)
	{
		WorldMode.Scope scope = worldScope;
		if (modeGuardActive && (scope == null || !scope.mode.recorded())) return;
		if (scope == null) scope = new WorldMode.Scope(WorldMode.Mode.STANDARD, null);
		if (client.getLocalPlayer() == null)
		{
			return;
		}
		String account = client.getLocalPlayer().getName();
		if (account == null || account.isBlank() || uploadsPaused()) return;
		boolean loaned = inLoanedArea();
		ChangeAwareEngineBridge localBridge = changeAwareBridge;
		SagaSeerLink.Credential credential = linkedCredential();
		boolean upload = localBridge != null && credential != null;
		LocalTrail trail = localTrail;
		if (!upload && trail == null) return;
		List<String> localOff = SyncPolicy.localOff(config);
		if (localBridge != null) localBridge.localOff(localOff);
		Set<String> off = localBridge != null ? localBridge.sectionsOff() : SyncPolicy.sectionsOf(localOff);
		offNow = off;
		Map<String, ChangeAwareSourceCapture.Observation> observations = new LinkedHashMap<>();
		readOwnCore(observations, off, loaned,
			() -> ChangeAwareSourceCapture.skills(client, Instant::now),
			() -> changeAwareInventorySource.read(account, client.getItemContainer(InventoryID.INV),
				this::itemName, Instant::now),
			() -> changeAwareEquipmentSource.readWorn(account, client.getItemContainer(InventoryID.WORN),
				this::itemName, Instant::now));
		if (!off.contains("state.quests")) observations.putAll(ChangeAwareSourceCapture.quests(client, Instant::now));
		read(observations, off, "state.diaries", () -> AchievementDiaryState.read(client, Instant::now));
		read(observations, off, "state.combatAchievements", () -> CombatAchievementsState.read(client, Instant::now));
		read(observations, off, "state.killCounts", () -> GameKillCounts.read(client, Instant::now));
		read(observations, off, "state.activities", () -> GameActivities.withTears(
			GameActivities.withClues(GameActivities.read(client, Instant::now), clueChat), tears,
			client.getVarpValue(VarPlayerID.QP), client.getOverallExperience()));
		read(observations, off, "state.account", () -> ChangeAwareSourceCapture.account(client, Instant::now));
		// Held rows are taken either way, so a type that is off keeps nothing.
		if (!off.contains("state.storage"))
		{
			storageLocations.observePouch(account, runePouch(), this::itemName, Instant.now());
			storageLocations.observeVarbits(account, StorageLocations.Location.TOOL_LEPRECHAUN,
				StorageLocations.toolLeprechaun(client::getVarbitValue), this::itemName, Instant.now());
			// The bolt pouch: an empty one is sent only after a filled one, so players without one get no empty place.
			Item[] bolts = StorageLocations.boltPouch(client::getVarbitValue);
			if (bolts != null && (bolts.length > 0 || storageLocations.sentVarbits(StorageLocations.Location.BOLT_POUCH)))
				storageLocations.observeVarbits(account, StorageLocations.Location.BOLT_POUCH, bolts, this::itemName, Instant.now());
			// The plank sack (unconfirmed in game), the same way.
			observeVarbitStore(account, StorageLocations.Location.PLANK_SACK, StorageLocations.plankSack(client::getVarbitValue));
			// The stored pickaxe, empty too, so one taken back while the plugin was off doesn't stay on the site.
			storageLocations.observeVarbits(account, StorageLocations.Location.TOA_PICKAXE,
				StorageLocations.toaPickaxe(client::getVarbitValue), this::itemName, Instant.now());
		}
		ChangeAwareSourceCapture.Observation storage = storageLocations.take(account);
		if (storage != null && !off.contains("state.storage")) observations.put("state.storage", storage);
		read(observations, off, "state.gim", () -> ChangeAwareSourceCapture.gim(client, Instant::now));
		boolean clanSettled = clanLeft || clanSettledTick >= 0L && currentGameTick() >= clanSettledTick;
		read(observations, off, "state.clan", () -> ChangeAwareSourceCapture.clan(client, Instant::now, clanSettled));
		ChangeAwareSourceCapture.Observation slayer = off.contains("state.slayer") && off.contains("state.slayerSuperiors")
			? null : GameSlayer.read(client, Instant::now);
		read(observations, off, "state.slayer", () -> slayer);
		read(observations, off, "state.hunterRumours", hunterRumourState::observation);
		read(observations, off, "state.raidRewards", raidRewards::observation);
		readTimePlayed(observations, off, client::getVarcIntValue, client::getVarbitValue);
		read(observations, off, "state.farming", gamePatches::observation);
		read(observations, off, "state.travel", () -> GameTravel.read(client, Instant::now));
		read(observations, off, "state.house", () -> HouseCapture.observation(client.getVarbitValue(VarbitID.POH_HOUSE_LOCATION),
			client.getVarbitValue(VarbitID.POH_SERVANT_TYPE), house, Instant.now()));
		read(observations, off, "state.music", () -> gameMusic.read(client, Instant::now));
		read(observations, off, "state.itemCharges", () -> ItemCharges.withChecked(ItemCharges.read(client, Instant::now), checkChat));
		read(observations, off, "state.stash", () -> stashUnits.observation(account));
		read(observations, off, "state.stashBoard", () -> stashBoard.observation(account));
		read(observations, off, "state.killLog", () -> killLog.observation(account));
		read(observations, off, "state.grandExchange", () -> GrandExchangeOffers.read(client.getGrandExchangeOffers(), grandExchangeSent,
			this::itemName, Instant::now));
		read(observations, off, "state.grandExchangeHistory", grandExchangeHistory::observation);
		ChangeAwareSourceCapture.Observation collectionLog = CollectionLogRows.observation(
			takeCollectionLogChanges(account, collectionLogIsLoggedIn()));
		if (collectionLog != null && !off.contains("state.collectionLog")) observations.put("state.collectionLog", collectionLog);
		read(observations, off, "state.logAnnouncements", () -> logAnnouncements.observation(account));
		read(observations, off, "state.deaths", () -> deathDetails.observation(account));
		read(observations, off, "state.slayerSuperiors", () -> { noteSlayerTask(account, slayer); return observedCounts.superiors(account); });
		read(observations, off, "state.dropAttempts", () -> observedCounts.attempts(account));
		read(observations, off, "state.consumables", () -> observedCounts.consumables(account));
		read(observations, off, "state.xpHits", () -> observedCounts.xpHits(account));
		if (collectionLogIsLoggedIn())
		{
			ChangeAwareSourceCapture.Observation bosses = BossRecordChatCapture.observation(
				bossRecordChatCapture.takeCounts(bossRecordCaptureSelector(), bossRecordCaptureAllowed()));
			if (bosses != null && !off.contains("state.bosses")) observations.put("state.bosses", bosses);
		}
		if (trail != null) trail.observe(scope.key(account), observations, keepPrelink(upload, scope));
		if (!upload) return;
		boolean full = fullResyncWanted;
		fullResyncWanted = false;
		localBridge.accountHash(client.getAccountHash());
		localBridge.atTrigger(account, scope, credential, observations,
			full, confirmsUnchanged(snapshotReason),
			() -> linkedCredential() == credential);
	}

	void readTimePlayed(Map<String, ChangeAwareSourceCapture.Observation> into, Set<String> off, IntUnaryOperator varc, IntUnaryOperator varbit)
	{
		read(into, off, "state.timePlayed", () -> playTimeState.read(varc, varbit, Instant::now));
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (inLoanedArea()) return;
		String skill = event.getSkill().getName();
		if (collectionLogIsLoggedIn() && reads("state.xpHits"))
			observedCounts.xp(currentCollectionLogName(), skill, event.getXp(), currentGameTick(), Instant.now());
		if (!gameTriggers.levelDiffers(skill, event.getLevel()) || !reads("state.skills")) return;
		if (gameTriggers.levelUp(skill, event.getLevel())) requestSnapshot("LEVEL_UP:" + skill.toUpperCase(Locale.ROOT));
	}

	private void observeVarbitStore(String account, StorageLocations.Location location, Item[] items)
	{
		if (items != null && (items.length > 0 || storageLocations.sentVarbits(location)))
			storageLocations.observeVarbits(account, location, items, this::itemName, Instant.now());
	}

	private static void read(Map<String, ChangeAwareSourceCapture.Observation> into, Set<String> off, String section,
		Supplier<ChangeAwareSourceCapture.Observation> source)
	{
		if (off.contains(section)) return;
		ChangeAwareSourceCapture.Observation read = source.get();
		if (read != null) into.put(section, read);
	}

	static void readOwnCore(Map<String, ChangeAwareSourceCapture.Observation> into, Set<String> off, boolean loaned,
		Supplier<ChangeAwareSourceCapture.Observation> skills,
		Supplier<ChangeAwareSourceCapture.Observation> inventory,
		Supplier<ChangeAwareSourceCapture.Observation> equipment)
	{
		if (loaned) return;
		read(into, off, "state.skills", skills);
		read(into, off, "state.inventory", inventory);
		read(into, off, "state.equipment", equipment);
	}

	/** The panel read when due (linked, not paused, logged in); the request runs on OkHttp's threads. */
	private void askForPanel()
	{
		ChangeAwareEngineBridge bridge = changeAwareBridge;
		SagaSeerLinkPanel panel = linkPanel;
		Instant now = Instant.now();
		WorldMode.Scope scope = worldScope;
		if (bridge == null || panel == null || panel.credential() == null || uploadsPaused() ||
			scope == null || !hasLocalPlayer() || !panelPull.due(now)) return;
		int session;
		synchronized (panelLock) { session = panelSession; }
		boolean asked = bridge.readPanel(client.getLocalPlayer().getName(), scope, (character, read) ->
		{
			// Kept only for the same session, link and bound character; checked and kept in one step against a reset.
			synchronized (panelLock)
			{
				if (session != panelSession || !character.equals(bridge.boundCharacter())) return;
				if (read != null) panelRead = read;
			}
			showSidebar();
		});
		if (asked) panelPull.started(now);
	}

	/** A session end or link change drops the panel's answer and refuses any still on its way. Any thread. */
	private void resetPanel()
	{
		synchronized (panelLock)
		{
			panelSession++;
			panelRead = null;
		}
		panelPull.reset();
		showSidebar();
	}

	private void showSidebar()
	{
		SagaSeerLinkPanel panel = linkPanel;
		LocalTrail trail = localTrail;
		if (panel == null || trail == null) return;
		ChangeAwareEngineBridge bridge = changeAwareBridge;
		LocalTrail.View local = LocalTrail.withSynced(trail.view(), bridge == null ? null : bridge.syncedRumours());
		WorldMode.Scope scope = worldScope;
		String plain = worldAccount == null ? local.account == null ? null : local.account.split("\\n", 2)[0] : worldAccount;
		String display = plain;
		String worldNote = worldNote(scope, bridge != null && bridge.boundFor(plain, scope), plain, lastLmsArea, lastArenaArea);
		if (scope != null)
		{
			if (scope.mode.label() != null && plain != null) display = plain + " · " + scope.mode.label();
		}
		Set<String> serverOff = bridge == null ? Set.of() : bridge.serverOff();
		PanelRead read;
		int generation;
		synchronized (panelLock)
		{
			read = panelRead;
			generation = panelSession;
		}
		panel.show(new SagaSeerLinkPanel.Sidebar(pausedByPlayer(), display,
			bridge == null ? null : bridge.installationLabel(), bridge == null ? null : bridge.lastSyncAt(),
			bridge == null ? null : bridge.prelinkState(), local, read, sectionsOffNow().contains("state.slayer"), generation, slayerKillsLeft,
			SagaSeerLinkPanel.collected(local.seenAt, bridge == null ? Map.of() : bridge.syncedAt(),
				SyncPolicy.sectionsOf(SyncPolicy.localOff(config)), serverOff, panel.credential() != null, Instant.now()),
			worldNote));
	}

	static String worldNote(WorldMode.Scope scope, boolean bound, String name, boolean lms, boolean arena)
	{
		if (scope == null) return null;
		if (!scope.mode.recorded()) return "SagaSeer doesn't record this world (beta, tournament, speedrun, no-save or Fresh Start).";
		if (scope.mode.temporary() && !bound)
			return "SagaSeer can't record Leagues or Deadman characters yet. Nothing from this world reaches " +
				(name == null ? "your main character" : name) + ".";
		if (lms) return "Last Man Standing: inventory, gear and stats paused.";
		if (arena) return "PvP Arena fight: inventory, gear and stats paused.";
		return null;
	}

	static boolean keepPrelink(boolean upload, WorldMode.Scope scope)
	{
		return !upload && scope != null && !scope.mode.temporary();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!SagaSeerConfig.GROUP.equals(event.getGroup())) return;
		// The bridge records a switch turned off at once, even while paused.
		refreshOff();
		if (clientThread != null) clientThread.invoke(this::dropWhatIsOff);
		showSidebar();
	}

	/**
	 * The data types off here or for the account, kept in a field because reads() asks many times a tick. Refreshed on a setting,
	 * a policy change, each upload and start.
	 */
	private volatile Set<String> offNow = Set.of();

	private Set<String> sectionsOffNow()
	{
		return offNow;
	}

	private void refreshOff()
	{
		List<String> localOff = SyncPolicy.localOff(config);
		ChangeAwareEngineBridge bridge = changeAwareBridge;
		if (bridge == null) { offNow = SyncPolicy.sectionsOf(localOff); return; }
		bridge.localOff(localOff);
		offNow = bridge.sectionsOff();
	}

	private boolean reads(String section)
	{
		return (!modeGuardActive || worldScope != null && worldScope.mode.recorded()) &&
			!pausedByPlayer() && !sectionsOffNow().contains(section);
	}

	private void dropWhatIsOff()
	{
		refreshOff();
		boolean paused = uploadsPaused();
		Set<String> off = sectionsOffNow();
		if (paused || off.contains("state.storage"))
		{
			storageLocations.endVisits();
			String character = client.getLocalPlayer() == null ? null : client.getLocalPlayer().getName();
			if (character != null) storageLocations.take(character);
		}
		gameTriggers.drop(paused, off);
		if (paused || off.contains("state.hunterRumours")) hunterRumourState.reset();
		if (paused || off.contains("state.stash")) { stashUnits.reset(); stashWantedAt = -1; }
		if (paused || off.contains("state.stashBoard")) stashBoard.reset();
		if (paused || off.contains("state.killLog")) killLog.reset();
		if (paused || off.contains("state.raidRewards")) raidRewards.reset();
		if (paused || off.contains("state.farming")) gamePatches.reset();
		if (paused || off.contains("state.bosses")) bossRecordChatCapture.reset();
		if (paused || off.contains("state.activities")) { clueChat.reset(); tears.reset(); }
		if (paused || off.contains("state.itemCharges")) checkChat.reset();
		if (paused || off.contains("state.house")) house.reset();
		if (paused || off.contains("state.grandExchangeHistory")) grandExchangeHistory.reset();
		if (paused || off.contains("state.logAnnouncements")) logAnnouncements.reset();
		if (paused || off.contains("state.deaths")) { deathDetails.reset(); deathCause.reset(); }
		if (paused || off.contains("state.deaths") || off.contains("state.inventory") || off.contains("state.equipment")) deathItems.reset();
		if (paused || off.contains("state.inventory") || off.contains("state.equipment")) deathDetails.clearItems();
		if (paused || off.contains("state.slayerSuperiors")) observedCounts.resetSuperiors();
		if (paused || off.contains("state.dropAttempts")) observedCounts.resetAttempts();
		if (paused || off.contains("state.consumables")) observedCounts.resetConsumables();
		if (paused || off.contains("state.xpHits")) observedCounts.resetXpHits();
		if (paused || off.contains("state.collectionLog")) collectionLogState.reset();
	}

	/** Only a rejected token (401) or a disabled account forgets the link; other 403s pause uploads and show a problem. */
	private void handleCaptureDenial(String installationId, int status, String body)
	{
		clientThread.invoke(() ->
		{
			SagaSeerLinkPanel panel = linkPanel;
			SagaSeerLink.Credential credential = panel == null ? null : panel.credential();
			if (credential == null || !credential.installationId.equals(installationId)) return;
			if (SagaSeerLink.forgetsOn(status, body)) { panel.forgetLink(); resetPanel(); }
			else if (status == 403)
			{
				snapshotSyncPaused = true;
				panel.showUploadError();
			}
		});
	}

	private SagaSeerLink.Credential linkedCredential()
	{
		SagaSeerLinkPanel panel = linkPanel;
		return uploadsPaused() || panel == null ? null : panel.credential();
	}

	/** Pause, or a server refusal: nothing is sent, the link is kept, and waiting uploads go out on resume. */
	boolean uploadsPaused()
	{
		return snapshotSyncPaused || pausedByPlayer() || modeGuardActive &&
			(worldScope == null || !worldScope.mode.recorded());
	}

	boolean pausedByPlayer()
	{
		return config != null && config.pauseUploads();
	}

	private String characterKey()
	{
		if (client.getLocalPlayer() == null || configManager == null || configManager.getProfile() == null)
		{
			return null;
		}
		return CharacterKey.key(configManager.getProfile().getId(), client.getLocalPlayer().getName());
	}

	private static String stripTags(String text)
	{
		return text == null ? "" : Text.removeTags(text);
	}

	@Provides
	SagaSeerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SagaSeerConfig.class);
	}
}
