package com.sagaseer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.GridLayout;
import java.awt.Insets;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

/**
 * The SagaSeer sidebar: linking at the top; then, when they apply, the in-game to-do list, the Slayer task and card, the pinned
 * Now step, the Recent feed and site shortcuts; at the bottom the account, last sync, Pause and two fold-outs. Redrawn only on a
 * change, never per tick.
 */
@Slf4j
final class SagaSeerLinkPanel extends PluginPanel
{
    private static final int POLL_MS = 3000;
    static final String WEBSITE = SagaSeerServiceOrigins.WEBSITE + "/app";
    static final int RECENT = 10;
    /** Narrower than the panel, so lines wrap. */
    static final int TEXT_WIDTH = 165;
    private static final String[][] SHORTCUTS = {{"Bank", "/bank"}, {"Slayer", "/slayer-hunter"}, {"Log", "/collection-log"}, {"Plan", "/goals"}};

    /** What the sidebar shows, built by the plugin. */
    static final class Sidebar
    {
        final boolean paused;
        final String character;
        final String installationLabel;
        final String lastSyncAt;
        final String prelinkState;
        final LocalTrail.View local;
        /** The panel read, or null before the first answer. */
        final PanelRead pulled;
        /** Slayer off: its task and card aren't shown. */
        final boolean slayerOff;
        /** The panel session this was built for: an older one arriving late is never drawn. */
        final int generation;
        /** Kills left as the client shows now (-1 when unknown). */
        final int liveKillsLeft;
        final List<Collected> collected;
        final String worldNote;
        Sidebar(boolean paused, String character, String installationLabel, String lastSyncAt, String prelinkState,
                LocalTrail.View local, PanelRead pulled, boolean slayerOff, int generation, int liveKillsLeft, List<Collected> collected,
                String worldNote)
        {
            this.worldNote = worldNote;
            this.liveKillsLeft = liveKillsLeft;
            this.pulled = pulled;
            this.slayerOff = slayerOff;
            this.generation = generation;
            this.paused = paused;
            this.character = character;
            this.installationLabel = installationLabel;
            this.lastSyncAt = lastSyncAt;
            this.prelinkState = prelinkState;
            this.local = local;
            this.collected = collected;
        }
    }

    /** The two local controls; both act on this computer only. */
    interface Actions
    {
        void togglePause();
        void clearLocalData();
    }

    private final SagaSeerLink link;
    private final Runnable onLinked;
    private final Actions actions;
    private final ExecutorService io = Executors.newSingleThreadExecutor(task ->
    {
        Thread thread = new Thread(task, "sagaseer-link");
        thread.setDaemon(true);
        return thread;
    });
    private final Timer poller = new Timer(POLL_MS, event -> poll());
    /** Redraws local timers without asking the server for another panel read. */
    private final Timer cardClock = new Timer(30_000, event -> render());
    private final JPanel contents = new JPanel();
    /** Where put() adds: the panel, or the open section card. */
    private JComponent target = contents;
    private final JLabel problem = new JLabel();
    private final JButton connect = new JButton("Link to SagaSeer");
    private final JButton reopen = new JButton("Open the approval page");
    private final JButton cancel = new JButton("Cancel");
    private final JButton disconnect = new JButton("Forget link on this computer");
    private final JButton pause = new JButton();
    private final JButton clear = new JButton("Clear local data");
    private final Set<String> expanded = new HashSet<>();
    /** Cards folded shut by clicking their title, for this session; every card starts open. */
    private final Set<String> folded = new HashSet<>();
    private NavigationButton navigation;
    private ClientToolbar toolbar;
    private boolean polling;
    /** Set by stop(): nothing restarts afterwards. */
    private volatile boolean stopped;
    private Sidebar shown;
    private final LatestOnly latest = new LatestOnly();
    private final ItemManager itemManager;

    SagaSeerLinkPanel(SagaSeerLink link, Runnable onLinked, Actions actions, ItemManager itemManager)
    {
        this.link = link;
        this.itemManager = itemManager;
        this.onLinked = onLinked;
        this.actions = actions;
        setLayout(new BorderLayout());
        contents.setLayout(new BoxLayout(contents, BoxLayout.Y_AXIS));
        contents.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
        add(contents, BorderLayout.NORTH);
        connect.addActionListener(event -> startLink());
        reopen.addActionListener(event ->
        {
            SagaSeerLink.Pending pending = link.pending();
            if (pending != null) LinkBrowser.browse(pending.browserUrl);
        });
        cancel.addActionListener(event -> io.execute(() -> { link.cancel(); refresh(); }));
        disconnect.addActionListener(event -> io.execute(() ->
        {
            try { link.forget(); }
            catch (IOException e) { log.warn("Could not remove the saved SagaSeer link"); }
            refresh();
        }));
        pause.addActionListener(event -> actions.togglePause());
        clear.addActionListener(event ->
        {
            if (JOptionPane.showConfirmDialog(this, "Delete the changes SagaSeer kept on this computer? Nothing on the "
                    + "website is touched.", "Clear local data", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
                actions.clearLocalData();
        });
        poller.setRepeats(true);
        cardClock.start();
        refresh();
    }

    void start(ClientToolbar toolbar)
    {
        navigation = NavigationButton.builder().tooltip("SagaSeer").icon(SagaSeerSidebarIcon.load())
                .priority(8).panel(this).build();
        toolbar.addNavigation(navigation);
        this.toolbar = toolbar;
    }

    void stop()
    {
        stopped = true;
        poller.stop();
        cardClock.stop();
        io.shutdownNow();
        if (toolbar != null && navigation != null) toolbar.removeNavigation(navigation);
    }

    SagaSeerLink.Credential credential() { return link.credential(); }

    /** The server says the link is gone. */
    void forgetLink()
    {
        io.execute(() ->
        {
            try { link.forget(); }
            catch (IOException e) { log.warn("Could not remove the saved SagaSeer link"); }
            refresh();
        });
    }

    void showUploadError() { SwingUtilities.invokeLater(() -> problem.setText("Upload problem; retrying later.")); }

    void clearUploadError() { SwingUtilities.invokeLater(() -> problem.setText("")); }

    /** Draws the sidebar unless a newer session's was drawn; any thread. */
    void show(Sidebar sidebar)
    {
        SwingUtilities.invokeLater(() ->
        {
            if (!latest.accept(sidebar.generation)) return;
            shown = sidebar;
            render();
        });
    }

    private void startLink()
    {
        connect.setEnabled(false);
        io.execute(() ->
        {
            try
            {
                SagaSeerLink.Pending pending = link.start();
                LinkBrowser.browse(pending.browserUrl);
                SwingUtilities.invokeLater(this::startPolling);
            }
            catch (IOException | RuntimeException e)
            {
                SwingUtilities.invokeLater(() -> problem.setText("Could not reach SagaSeer. Try again."));
            }
            refresh();
        });
    }

    /** Swing thread. A stopped panel never restarts its timer. */
    void startPolling()
    {
        if (!stopped) poller.start();
    }

    boolean pollingForTest() { return poller.isRunning(); }

    private void poll()
    {
        if (polling || stopped) return;
        polling = true;
        io.execute(() ->
        {
            SagaSeerLink.PollResult result;
            try { result = link.poll(); }
            catch (IOException | RuntimeException e) { result = SagaSeerLink.PollResult.WAITING; }
            SagaSeerLink.PollResult finished = result;
            SwingUtilities.invokeLater(() ->
            {
                polling = false;
                if (finished != SagaSeerLink.PollResult.WAITING) poller.stop();
                if (finished == SagaSeerLink.PollResult.EXPIRED) problem.setText("That link request expired. Try again.");
                if (finished == SagaSeerLink.PollResult.FAILED) problem.setText("Linking was refused. Try again.");
            });
            if (finished == SagaSeerLink.PollResult.LINKED) onLinked.run();
            refresh();
        });
    }

    private void refresh() { SwingUtilities.invokeLater(this::render); }

    private void render()
    {
        boolean linked = link.credential() != null;
        SagaSeerLink.Pending pending = link.pending();
        Sidebar s = shown;
        Instant now = Instant.now();
        contents.removeAll();
        target = contents;
        // Top: who this is and whether it's syncing.
        header(linked, s, now);
        if (s != null && s.worldNote != null && s.worldNote.startsWith("SagaSeer doesn't record"))
        {
            small(s.worldNote);
            done();
            return;
        }
        if (pending != null)
        {
            beginCard(ColorScheme.BRAND_ORANGE);
            title("Finish linking", ColorScheme.BRAND_ORANGE);
            text("Approve in your browser to link this character to SagaSeer.");
            put(reopen);
            put(cancel);
            endCard();
        }
        else if (!linked)
        {
            beginCard(ColorScheme.BRAND_ORANGE);
            title("Link to SagaSeer", ColorScheme.BRAND_ORANGE);
            small("See this character on the website, with history that's kept across sessions.");
            put(connect);
            endCard();
        }
        if (linked && s != null && "WAITING".equals(s.prelinkState))
        {
            beginCard(ColorScheme.BRAND_ORANGE);
            text("Changes kept before you linked: choose Import history or Start fresh on the website (Data & privacy).");
            endCard();
        }
        if (!problem.getText().isEmpty()) put(problem);
        connect.setEnabled(!linked && pending == null);
        if (s != null && s.worldNote != null && s.worldNote.startsWith("SagaSeer can't record"))
        {
            small(s.worldNote);
            done();
            return;
        }
        PanelRead pulled = linked && s != null ? s.pulled : null;
        String asOf = pulled != null && pulled.stale(now) ? " (as of " + when(pulled.at.toString(), now) + ")" : "";
        if (pulled != null && !pulled.todo.isEmpty())
        {
            beginCard(ColorScheme.BRAND_ORANGE);
            if (cardTitle("todo", "To do in game · " + pulled.todo.size() + asOf, null, ColorScheme.BRAND_ORANGE))
                for (String line : pulled.todo) text(line);
            endCard();
        }
        if (pulled != null && recentKill(pulled.killing, now))
        {
            PanelRead.Killing kill = pulled.killing;
            // The server's read can be a minute behind; a later count this client already saw wins.
            int kc = newestKc(kill, s.local != null ? s.local.events : List.of());
            beginCard(null);
            if (cardTitle("killing", kill.boss, "kc " + LocalEvents.number(kc), Color.WHITE) && kill.obtained != null && kill.total != null)
            {
                if (kill.missing.isEmpty()) title("Log complete", ColorScheme.BRAND_ORANGE);
                else
                {
                    title("Log " + kill.obtained + " of " + kill.total + " · " + kill.missing.get(0).name + " missing", ColorScheme.BRAND_ORANGE);
                    for (int i = 0; i < kill.missing.size(); i++)
                    {
                        PanelRead.Missing drop = kill.missing.get(i);
                        small(missingLine(new PanelRead.Missing(drop.name, drop.rate, chanceAt(drop, kc, kill.kc), drop.perKill), i == 0));
                    }
                }
            }
            endCard();
        }
        if (pulled != null && !pulled.ready.isEmpty())
        {
            List<PanelRead.Ready> visible = new ArrayList<>(pulled.ready);
            visible.sort((a, b) -> Boolean.compare(minutesUntil(a.at, now) > 0, minutesUntil(b.at, now) > 0));
            beginCard(ColorScheme.BRAND_ORANGE);
            if (cardTitle("ready", "Ready now · " + visible.size(), null, ColorScheme.BRAND_ORANGE)) for (PanelRead.Ready ready : visible)
            {
                long minutes = minutesUntil(ready.at, now);
                JPanel line = row();
                line.add(new JLabel(ready.name), BorderLayout.WEST);
                JLabel state = new JLabel(minutes == 0 ? "Ready" : "in " + minutes + " min");
                state.setForeground(minutes == 0 ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
                line.add(state, BorderLayout.EAST);
                line.setToolTipText(ready.detail);
                put(line);
            }
            endCard();
        }
        // Slayer: the task from the client, the card from the site.
        LocalTrail.SlayerTask slayer = s != null && s.local != null && !s.slayerOff ? s.local.slayer : null;
        long left = slayer == null ? 0 : s.liveKillsLeft >= 0 ? s.liveKillsLeft : slayer.remaining;
        if (slayer != null && left > 0)
        {
            beginCard(null);
            if (cardTitle("slayer", "Slayer task", null, Color.WHITE))
            {
                JPanel head = row();
                head.add(bold(slayer.name), BorderLayout.WEST);
                head.add(bold(LocalEvents.number(left) + " left"), BorderLayout.EAST);
                put(head);
                if (slayer.original != null && slayer.original >= left)
                    bar((int) Math.min(Integer.MAX_VALUE, slayer.original), (int) (slayer.original - left));
                if (pulled != null && pulled.slayerTask != null && pulled.slayerTask.equalsIgnoreCase(slayer.name)) card(pulled);
            }
            endCard();
        }
        // Hunter rumour: the current one from the client (Slayer's data type covers it).
        HunterRumourDialogue.Rumour rumour = s != null && s.local != null && !s.slayerOff ? s.local.rumour : null;
        if (rumour != null)
        {
            beginCard(null);
            if (cardTitle("hunter", "Hunter rumour", null, Color.WHITE))
            {
                JPanel head = row();
                head.add(bold(Character.toUpperCase(rumour.creature.charAt(0)) + rumour.creature.substring(1)), BorderLayout.WEST);
                JLabel tier = new JLabel(rumour.tier);
                tier.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
                head.add(tier, BorderLayout.EAST);
                put(head);
                small("From " + rumour.hunter);
            }
            endCard();
        }
        if (pulled != null && pulled.goal != null)
        {
            PanelRead.Goal goal = pulled.goal;
            beginCard(null);
            if (cardTitle("plan", "Your plan", null, Color.WHITE))
            {
                JPanel head = row();
                head.add(bold(html(goal.name)), BorderLayout.WEST);
                head.add(bold(goal.percent + "%"), BorderLayout.EAST);
                put(head);
                bar(100, goal.percent);
                if (goal.now != null)
                {
                    JPanel step = row();
                    step.add(new JLabel(html("Now: " + goal.now.text, 110)), BorderLayout.WEST);
                    step.add(new JLabel(goal.now.from != null && goal.now.to != null ? goal.now.from + " → " + goal.now.to : ""), BorderLayout.EAST);
                    put(step);
                }
                if (goal.next != null) small("Then: " + goal.next);
            }
            endCard();
        }
        else if (pulled != null && pulled.now != null)
        {
            beginCard(null);
            if (cardTitle("plan", "Your plan · Now", null, Color.WHITE)) text(pulled.now);
            endCard();
        }
        // Recent: the 10 latest notable events, then the site.
        beginCard(null);
        if (cardTitle("recent", "Recent Events", null, Color.WHITE))
        {
            List<LocalTrail.Event> events = s != null && s.local != null ? s.local.events : List.of();
            if (events.isEmpty()) small("Nothing yet: levels, log items and kill counts show here.");
            for (LocalTrail.Event event : events.subList(0, Math.min(RECENT, events.size()))) event(event, now);
            if (linked) put(linkLabel("See all on the site", WEBSITE + "/history"));
        }
        endCard();
        if (linked)
        {
            JPanel buttons = new JPanel(new GridLayout(1, 0, 4, 0));
            buttons.setOpaque(false);
            for (String[] shortcut : SHORTCUTS)
            {
                JButton button = new JButton(shortcut[0]);
                button.setMargin(new Insets(2, 0, 2, 0));
                button.addActionListener(event -> LinkBrowser.browse(WEBSITE + shortcut[1]));
                buttons.add(button);
            }
            put(buttons);
        }
        // Bottom: the two fold-outs and Pause on one row.
        pause.setText(s != null && s.paused ? "Resume" : "Pause");
        JPanel footer = row();
        JPanel links = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
        links.setOpaque(false);
        links.add(foldLink("Data", DATA));
        links.add(Box.createHorizontalStrut(10));
        links.add(foldLink("Privacy", PRIVACY));
        footer.add(links, BorderLayout.WEST);
        footer.add(pause, BorderLayout.EAST);
        contents.add(Box.createVerticalStrut(2));
        put(footer);
        if (s != null && s.worldNote != null) small(s.worldNote);
        else if (s != null && s.paused) small("Paused: nothing is read, kept or sent.");
        if (expanded.contains(DATA))
        {
            beginCard(null);
            title(DATA, Color.WHITE);
            List<Collected> rows = s == null ? List.of() : s.collected;
            if (rows.isEmpty()) small("Nothing collected yet.");
            else
            {
                // The normal case once, then only what needs a look.
                small(summary(rows, linked, now));
                for (Collected row : rows) if (!row.fine) small(row.label + " · " + row.status);
                if (fold("Show all")) for (Collected row : rows) twoColumns(row.label, row.status);
            }
            small("Turn types on or off: SagaSeer's settings (the wrench).");
            endCard();
        }
        if (expanded.contains(PRIVACY))
        {
            beginCard(null);
            title(PRIVACY, Color.WHITE);
            String kept = s != null && s.local != null && s.local.kept != null
                ? " and " + LocalEvents.number(s.local.kept.records) + (s.local.kept.records == 1 ? " change" : " changes") + " from before linking" : "";
            if (linked && s != null && s.installationLabel != null) small("This computer: " + s.installationLabel);
            small("Kept on this computer: your Recent list" + kept + ". Never sent.");
            if (s != null && "IMPORTED".equals(s.prelinkState)) small("Your history from before linking was imported.");
            JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 4));
            buttons.setOpaque(false);
            buttons.add(clear);
            if (linked) buttons.add(disconnect);
            put(buttons);
            endCard();
        }
        done();
    }

    private static final String DATA = "Data sent from this computer";
    private static final String PRIVACY = "Privacy and unlink";

    static boolean recentKill(PanelRead.Killing killing, Instant now)
    {
        return killing != null && !now.isBefore(killing.killedAt) && now.isBefore(killing.killedAt.plusSeconds(600));
    }

    static long minutesUntil(Instant at, Instant now)
    {
        return at == null ? 0 : Math.max(0, (Duration.between(now, at).toSeconds() + 59) / 60);
    }

    /** The newest kill count for the card's boss: the latest of this client's Recent kill-count rows seen at or after the server's
     * reading, else the server's. By time, never the highest: a reset Hardcore life starts lower. */
    static int newestKc(PanelRead.Killing kill, List<LocalTrail.Event> events)
    {
        Long newest = null;
        Instant newestAt = null;
        for (LocalTrail.Event event : events)
        {
            if (event.to == null || !("kc:" + kill.boss).equalsIgnoreCase(event.merge)) continue;
            Instant at;
            try { at = Instant.parse(event.at); }
            catch (RuntimeException unreadable) { continue; }
            if (at.isBefore(kill.killedAt) || newestAt != null && !at.isAfter(newestAt)) continue;
            newest = event.to;
            newestAt = at;
        }
        return newest == null ? kill.kc : (int) Math.min(Integer.MAX_VALUE, newest);
    }

    /** The chance of having the drop by kc kills from the server's per-kill chance (rolls included), as the server works it out;
     * the server's own when kc is its count or it sent no per-kill chance. */
    static int chanceAt(PanelRead.Missing drop, int kc, int serverKc)
    {
        if (kc == serverKc || drop.perKill == null) return drop.chance;
        return (int) Math.round(100 * -Math.expm1(kc * Math.log1p(-drop.perKill)));
    }

    static String missingLine(PanelRead.Missing drop, boolean first)
    {
        if (!first) return drop.name + " " + drop.rate;
        return "Rate unknown".equals(drop.rate) ? "Drop rate unknown"
            : drop.rate + " · " + drop.chance + "% chance you'd have it by now";
    }

    /** The character and its sync state: green once synced, orange while paused. */
    private void header(boolean linked, Sidebar s, Instant now)
    {
        beginCard(null);
        JPanel head = row();
        String[] parts = headerParts(linked && s != null && s.character != null ? s.character : "SagaSeer");
        JLabel name = bold(parts[0]);
        name.setForeground(Color.WHITE);
        head.add(name, BorderLayout.WEST);
        JLabel state = new JLabel(!linked ? "Not linked" : s != null && s.paused ? "Paused"
            : s == null || s.lastSyncAt == null ? "No upload yet" : "● Synced " + when(s.lastSyncAt, now));
        state.setForeground(!linked || s == null || s.lastSyncAt == null && !s.paused ? ColorScheme.LIGHT_GRAY_COLOR
            : s.paused ? ColorScheme.BRAND_ORANGE : ColorScheme.PROGRESS_COMPLETE_COLOR);
        head.add(state, BorderLayout.EAST);
        put(head);
        if (parts[1] != null)
        {
            JLabel mode = new JLabel(parts[1]);
            mode.setForeground(ColorScheme.BRAND_ORANGE);
            put(mode);
        }
        endCard();
    }

    /** "Name · Hardcore Worlds" as the name and the world mode (null on a normal world): the mode gets its own line. */
    static String[] headerParts(String shown)
    {
        int split = shown.indexOf(" · ");
        return split < 0 ? new String[] {shown, null} : new String[] {shown.substring(0, split), shown.substring(split + 3)};
    }

    /** A section card: what put() adds goes inside it until endCard(); an accent draws a bar on its left edge. */
    private void beginCard(Color accent)
    {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        card.setBorder(accent == null ? BorderFactory.createEmptyBorder(6, 8, 2, 8)
            : BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, accent),
                BorderFactory.createEmptyBorder(6, 5, 2, 8)));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        contents.add(card);
        contents.add(Box.createVerticalStrut(6));
        target = card;
    }

    private void endCard() { target = contents; }

    private void done()
    {
        target = contents;
        contents.revalidate();
        contents.repaint();
    }

    /** A card's title that folds it: a click hides or shows the rest of the card. With a value, it sits on the right. */
    private boolean cardTitle(String key, String text, String value, Color color)
    {
        boolean open = !folded.contains(key);
        JPanel line = row();
        JLabel name = bold((open ? "▾ " : "▸ ") + text);
        name.setForeground(color);
        line.add(name, BorderLayout.WEST);
        if (value != null) line.add(new JLabel(value), BorderLayout.EAST);
        toggles(line, folded, key);
        put(line);
        return open;
    }

    /** A click on this flips the key in the set (open or folded) and redraws. */
    private void toggles(JComponent part, Set<String> set, String key)
    {
        part.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        part.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                if (!set.remove(key)) set.add(key);
                render();
            }
        });
    }

    /** The 6px orange progress bar the Slayer and plan cards share. */
    private void bar(int max, int value)
    {
        JProgressBar bar = new JProgressBar(0, max);
        bar.setValue(value);
        bar.setForeground(ColorScheme.BRAND_ORANGE);
        bar.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        bar.setBorder(null);
        bar.setPreferredSize(new Dimension(190, 6));
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
        put(bar);
    }

    /** A card's bold title. */
    private void title(String text, Color color)
    {
        JLabel label = bold(text);
        label.setForeground(color);
        put(label);
    }

    /** A see-through row for a name on the left and a value on the right. */
    private static JPanel row()
    {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        return row;
    }

    /** A footer link that opens or closes one fold-out section. */
    private JLabel foldLink(String text, String key)
    {
        JLabel label = new JLabel((expanded.contains(key) ? "▾ " : "▸ ") + text);
        label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        toggles(label, expanded, key);
        return label;
    }

    /** The Slayer card: stance, style, prayer and cannon as tags; Location as a row; item lists as icons; the rest under More. */
    private void card(PanelRead pulled)
    {
        if (pulled.card.isEmpty() && pulled.items.isEmpty()) { small("No card saved for this task yet."); return; }
        Card card = Card.of(pulled.card);
        if (!card.tags.isEmpty())
        {
            StringBuilder tags = new StringBuilder("<html><body style='width:" + TEXT_WIDTH + "px'>");
            for (String[] tag : card.tags)
                tags.append("<span style='background-color:").append(tag[1]).append(";color:#e8e8e8'>&nbsp;").append(escape(tag[0])).append("&nbsp;</span> ");
            put(new JLabel(tags.append("</body></html>").toString()));
        }
        for (String[] row : card.rows) labelled(row[0], row[1]);
        for (Map.Entry<String, List<PanelRead.Item>> list : pulled.items.entrySet()) icons(list.getKey(), list.getValue());
        if (!card.more.isEmpty() && fold("More: " + String.join(", ", card.moreNames()).toLowerCase(Locale.ROOT)))
            for (String[] row : card.more) labelled(row[0], row[1]);
    }

    /** Item icons, five to a row like the loot tracker; name and note on hover, an orange line marks a note. A thin line over
     * each group's label keeps Bring, Bank and Alch apart. */
    private void icons(String label, List<PanelRead.Item> items)
    {
        JPanel heading = row();
        heading.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, ColorScheme.MEDIUM_GRAY_COLOR),
            BorderFactory.createEmptyBorder(4, 0, 0, 0)));
        heading.add(label(label), BorderLayout.WEST);
        put(heading);
        JPanel grid = new JPanel(new GridLayout(0, 5, 1, 1));
        for (PanelRead.Item item : items)
        {
            JLabel icon = new JLabel();
            icon.setHorizontalAlignment(JLabel.CENTER);
            icon.setPreferredSize(new Dimension(36, 32));
            icon.setOpaque(true);
            icon.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            icon.setToolTipText(item.note == null ? item.name : item.name + ": " + item.note);
            if (item.note != null) icon.setBorder(BorderFactory.createMatteBorder(0, 0, 2, 0, ColorScheme.BRAND_ORANGE));
            itemManager.getImage(item.id).addTo(icon);
            grid.add(icon);
        }
        put(grid);
    }

    private static JLabel label(String text)
    {
        JLabel name = new JLabel(text);
        name.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        name.setFont(name.getFont().deriveFont(name.getFont().getSize2D() - 1f));
        return name;
    }

    /** A grey label over its value, cut to two lines with the full text on hover. */
    private void labelled(String label, String value)
    {
        put(label(label));
        JLabel text = new JLabel(html(Card.clip(value, 70)));
        if (value.length() > 70) text.setToolTipText("<html><body style='width:240px'>" + escape(value) + "</body></html>");
        put(text);
    }

    private void twoColumns(String left, String right)
    {
        JPanel row = row();
        JLabel name = new JLabel(left), status = new JLabel(right);
        status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        row.add(name, BorderLayout.WEST);
        row.add(status, BorderLayout.EAST);
        put(row);
    }

    private static JLabel bold(String text)
    {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    /** A Slayer card's "Label: value" lines, sorted for the panel. */
    static final class Card
    {
        final List<String[]> tags = new ArrayList<>();
        final List<String[]> rows = new ArrayList<>();
        final List<String[]> more = new ArrayList<>();

        static Card of(List<String> lines)
        {
            Card card = new Card();
            for (String line : lines)
            {
                int split = line.indexOf(": ");
                String label = split < 0 ? "Note" : line.substring(0, split), value = split < 0 ? line : line.substring(split + 2);
                switch (label)
                {
                    case "Stance": card.tags.add(new String[] {value, "Do".equals(value) ? "#2e6b3a" : "#555555"}); break;
                    case "Style": card.tags.add(new String[] {value, "#3d4a66"}); break;
                    case "Protect": card.tags.add(new String[] {"Protect: " + value, "#3d4a66"}); break;
                    case "Bring": card.tags.add(new String[] {"Bring: " + value, "#4d4433"}); break;
                    case "Cannon": card.tags.add(new String[] {"Cannon", "#4d4433"}); break;
                    case "Location": card.rows.add(new String[] {label, value}); break;
                    default: card.more.add(new String[] {label, value}); break;
                }
            }
            return card;
        }

        List<String> moreNames()
        {
            List<String> names = new ArrayList<>();
            for (String[] row : more) names.add(row[0]);
            return names;
        }

        static String clip(String text, int max)
        {
            if (text.length() <= max) return text;
            int cut = text.lastIndexOf(' ', max - 1);
            return text.substring(0, cut > max / 2 ? cut : max - 1) + "…";
        }
    }

    /** A data type's line in "Data sent from this computer". */
    static final class Collected
    {
        final String label;
        final String status;
        final boolean fine;
        final String at;
        Collected(String label, String status, boolean fine, String at)
        {
            this.label = label;
            this.status = status;
            this.fine = fine;
            this.at = at;
        }
    }

    static String summary(List<Collected> rows, boolean linked, Instant now)
    {
        int fine = 0;
        String latest = null;
        for (Collected row : rows)
        {
            if (!row.fine) continue;
            fine++;
            if (row.at != null && (latest == null || row.at.compareTo(latest) > 0)) latest = row.at;
        }
        String when = latest == null ? "" : " · " + when(latest, now);
        return linked ? "✓ " + fine + " of " + rows.size() + " up to date" + when : "✓ " + fine + " collected on this computer" + when;
    }

    /** A fold-out heading; true while open. */
    private boolean fold(String title)
    {
        boolean open = expanded.contains(title);
        JLabel label = new JLabel((open ? "▾ " : "▸ ") + title);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        toggles(label, expanded, title);
        target.add(Box.createVerticalStrut(4));
        put(label);
        return open;
    }

    private JLabel linkLabel(String text, String url)
    {
        JLabel label = new JLabel("<html><u>" + escape(text) + "</u></html>");
        label.setForeground(ColorScheme.BRAND_ORANGE);
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e) { LinkBrowser.browse(url); }
        });
        return label;
    }

    private void text(String value) { put(new JLabel(html(value))); }

    private void small(String value)
    {
        JLabel label = new JLabel(html(value));
        label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        put(label);
    }

    /** A Recent row; its context shows on hover. */
    private void event(LocalTrail.Event event, Instant now)
    {
        JPanel line = row();
        JLabel label = new JLabel("<html><body style='width:" + (TEXT_WIDTH - 40) + "px'>" + escape(event.title) + "</body></html>");
        JLabel time = new JLabel(when(event.at, now));
        time.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        time.setVerticalAlignment(JLabel.TOP);
        line.add(label, BorderLayout.CENTER);
        line.add(time, BorderLayout.EAST);
        if (!event.details.isEmpty()) line.setToolTipText("<html>" + escape(String.join("\n", event.details)).replace("\n", "<br>") + "</html>");
        put(line);
    }

    private void put(JComponent part)
    {
        part.setAlignmentX(Component.LEFT_ALIGNMENT);
        target.add(part);
        target.add(Box.createVerticalStrut(4));
    }

    static String html(String value) { return html(value, TEXT_WIDTH); }

    private static String html(String value, int width) { return "<html><body style='width:" + width + "px'>" + escape(value) + "</body></html>"; }

    private static String escape(String value)
    {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String when(String instant, Instant now)
    {
        if (instant == null) return "";
        ZonedDateTime at = Instant.parse(instant).atZone(ZoneId.systemDefault());
        LocalDate today = now.atZone(ZoneId.systemDefault()).toLocalDate();
        return at.toLocalDate().equals(today) ? at.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT))
            : at.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH));
    }

    /**
     * Each data type: off, or how up to date. Linked, fine when synced today with nothing newer seen; not linked, fine once
     * collected. Types never seen are left out.
     */
    static List<Collected> collected(Map<String, String> seenAt, Map<String, String> syncedAt, Set<String> localOff,
                                     Set<String> serverOff, boolean linked, Instant now)
    {
        List<Collected> rows = new ArrayList<>();
        LocalDate today = now.atZone(ZoneId.systemDefault()).toLocalDate();
        for (Map.Entry<String, List<String>> category : SyncPolicy.CATEGORIES.entrySet())
        {
            String label = SyncPolicy.LABELS.get(category.getKey());
            if (serverOff.containsAll(category.getValue())) { rows.add(new Collected(label, "off for your account", false, null)); continue; }
            if (localOff.containsAll(category.getValue())) { rows.add(new Collected(label, "off on this computer", false, null)); continue; }
            String seen = latest(seenAt, category.getValue());
            String synced = latest(syncedAt, category.getValue());
            if (seen == null && synced == null) continue;
            if (!linked) { rows.add(new Collected(label, "collected " + when(seen, now), true, seen)); continue; }
            if (synced == null) { rows.add(new Collected(label, "not synced yet", false, null)); continue; }
            boolean fine = Instant.parse(synced).atZone(ZoneId.systemDefault()).toLocalDate().equals(today)
                && (seen == null || Instant.parse(synced).compareTo(Instant.parse(seen)) >= 0);
            rows.add(new Collected(label, fine ? when(synced, now) : "last synced " + when(synced, now), fine, synced));
        }
        return rows;
    }

    private static String latest(Map<String, String> times, List<String> sections)
    {
        String latest = null;
        for (String section : sections)
        {
            String at = times.get(section);
            if (at != null && (latest == null || at.compareTo(latest) > 0)) latest = at;
        }
        return latest;
    }
}
