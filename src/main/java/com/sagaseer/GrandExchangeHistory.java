package com.sagaseer;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.sagaseer.ChangeAwareSourceCapture.Observation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.util.Text;

/**
 * The state.grandExchangeHistory rows: the trades the game's own GE history window lists, read once each time it opens. The game
 * builds six children per trade (script 1645): the side text is child 2, the item graphic child 4 (its own id and quantity, no
 * text parsing) and the price text the last; a sale's text can show the gross and tax. The window shows no time, so none is sent.
 * A window this class doesn't understand sends nothing rather than a misread row; the server decides which rows it already has.
 */
final class GrandExchangeHistory
{
    static final int CHILDREN_PER_ROW = 6;
    /** The list must stay one length this long: the game sends trades one at a time. */
    static final int SETTLE_TICKS = 2;
    static final int MAX_ROWS = 500;

    private static final Pattern COINS = Pattern.compile("^([0-9][0-9,]{0,18}) coins?$");
    private static final Pattern FEE = Pattern.compile("\\(([0-9][0-9,]{0,18}) - ([0-9][0-9,]{0,18})\\)");

    /** The four things read from one trade's children. */
    interface Entry
    {
        String sideText();
        int itemId();
        int quantity();
        String priceText();
    }

    private JsonArray latest;
    private Instant latestAt;
    private int seenCount = -1;
    private int stableTicks;
    private boolean read;

    /** Closed, or a session or data type ended: the next opening reads afresh. */
    void closed()
    {
        seenCount = -1;
        stableTicks = 0;
        read = false;
    }

    /** Nothing carries over a session or a data type being turned off. */
    void reset()
    {
        closed();
        latest = null;
        latestAt = null;
    }

    /** Each tick (client thread); true when the window was just read. */
    boolean tick(Client client, IntFunction<String> names, Instant at)
    {
        if (client == null) return false;
        Widget list = client.getWidget(InterfaceID.GeHistory.LIST);
        if (list == null || list.isHidden())
        {
            closed();
            return false;
        }
        Widget[] children = list.getDynamicChildren();
        int count = children == null ? 0 : children.length;
        if (count != seenCount)
        {
            seenCount = count;
            stableTicks = 0;
            return false;
        }
        if (read || count == 0 || ++stableTicks < SETTLE_TICKS) return false;
        read = true;
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i + CHILDREN_PER_ROW <= count; i += CHILDREN_PER_ROW)
        {
            Widget side = children[i + 2], item = children[i + 4], price = children[i + CHILDREN_PER_ROW - 1];
            if (side == null || item == null || price == null) return false;
            entries.add(new Entry()
            {
                @Override public String sideText() { return side.getText(); }
                @Override public int itemId() { return item.getItemId(); }
                @Override public int quantity() { return item.getItemQuantity(); }
                @Override public String priceText() { return price.getText(); }
            });
        }
        JsonArray rows = rows(count, entries, names);
        if (rows == null || rows.size() == 0) return false;
        latest = rows;
        latestAt = at;
        return true;
    }

    /** The section value of the last window read, or nothing before one has been. */
    Observation observation()
    {
        if (latest == null) return Observation.notRead();
        return new Observation(latest.deepCopy(), ChangeAwareSourceCapture.time(latestAt));
    }

    /** The rows of a window; null when it isn't a trade list we understand. */
    static JsonArray rows(int children, List<Entry> entries, IntFunction<String> names)
    {
        if (children == 0 || children % CHILDREN_PER_ROW != 0 || entries.size() != children / CHILDREN_PER_ROW
            || entries.size() > MAX_ROWS) return null;
        JsonArray rows = new JsonArray();
        for (Entry entry : entries)
        {
            String side = plain(entry.sideText());
            boolean sell = "Sold:".equals(side);
            if (!sell && !"Bought:".equals(side)) return null;
            int itemId = entry.itemId(), quantity = entry.quantity();
            String name = itemId < 1 ? null : names.apply(itemId);
            if (itemId < 1 || quantity < 1 || name == null || name.isEmpty()) return null;
            String priceText = entry.priceText();
            if (priceText == null) return null;
            // The first line of the price text: what was paid, or (a sale) received.
            String firstLine = plain(priceText.split("(?i)<br>", 2)[0]);
            Matcher coins = COINS.matcher(firstLine);
            if (!coins.matches()) return null;
            Long paid = number(coins.group(1));
            Long total = paid, tax = null;
            if (sell)
            {
                Matcher fee = FEE.matcher(plain(priceText));
                if (fee.find())
                {
                    total = number(fee.group(1));
                    tax = number(fee.group(2));
                }
                else
                    tax = 0L; // the game shows the fee only when there is one
            }
            if (total == null || total < 1 || (sell && tax == null)) return null;
            JsonObject row = new JsonObject();
            row.addProperty("side", sell ? "SELL" : "BUY");
            row.addProperty("itemId", itemId);
            row.addProperty("name", name);
            row.addProperty("quantity", quantity);
            row.addProperty("total", total);
            if (tax == null) row.add("tax", JsonNull.INSTANCE); else row.addProperty("tax", tax);
            rows.add(row);
        }
        return rows;
    }

    private static String plain(String text)
    {
        return text == null ? "" : Text.removeTags(text).trim();
    }

    private static Long number(String digits)
    {
        try
        {
            return Long.parseLong(digits.replace(",", ""));
        }
        catch (NumberFormatException tooBig)
        {
            return null;
        }
    }
}
