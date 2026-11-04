package com.sagaseer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.api.Item;
import net.runelite.api.gameval.InventoryID;

/** Account-scoped copies of the two carried containers (client thread). */
final class DeathItems
{
    /** Before: the items held when the death fires. After: the respawn tick's first container events. */
    static final int SETTLE_TICKS = 2;
    static final int QUIET_TICKS = 10;
    static final int GIVE_UP_TICKS = 40;

    static final class Stack
    {
        final int id, quantity;
        Stack(int id, int quantity) { this.id = id; this.quantity = quantity; }
    }

    static final class Change
    {
        final List<Stack> left, appeared;
        Change(List<Stack> left, List<Stack> appeared) { this.left = left; this.appeared = appeared; }
    }

    static final class Result
    {
        final String account, at;
        final Change change; // null means the observation could not be completed
        Result(String account, String at, Change change) { this.account = account; this.at = at; this.change = change; }
    }

    private static final class Image
    {
        final List<Stack> slots;
        final boolean known;
        Image(List<Stack> slots, boolean known) { this.slots = slots; this.known = known; }
    }

    private static final class Pending
    {
        final String account, at;
        final int deathTick;
        final Image beforeInv, beforeWorn;
        boolean loading, loggedIn;
        int respawnTick = -1;
        Image afterInv, afterWorn;
        boolean eventOnRespawn, laterEvent;
        Pending(String account, String at, int tick, Image inv, Image worn)
        { this.account = account; this.at = at; deathTick = tick; beforeInv = inv; beforeWorn = worn; }
    }

    private String account;
    private Image inv, worn;
    private Image firstInvThisTick, firstWornThisTick;
    private int eventTick = -1;
    private final Map<String, Integer> lastDeathTick = new HashMap<>();
    private Pending pending;

    void reset()
    {
        account = null; inv = worn = null; eventTick = -1;
        firstInvThisTick = firstWornThisTick = null; pending = null; lastDeathTick.clear();
    }

    /** A logout clears the live tracker but keeps a pending death. */
    void logout()
    {
        inv = worn = null; eventTick = -1;
        firstInvThisTick = firstWornThisTick = null;
    }

    void switchAccount(String name)
    {
        if (name == null || name.isBlank()) return;
        if (account != null && !account.equals(name))
        {
            logout(); pending = null; lastDeathTick.clear();
        }
        account = name;
    }

    void container(String name, int tick, int containerId, Item[] items)
    {
        if (containerId != InventoryID.INV && containerId != InventoryID.WORN) return;
        switchAccount(name);
        if (account == null) return;
        if (eventTick != tick)
        {
            eventTick = tick; firstInvThisTick = firstWornThisTick = null;
        }
        Image copy = image(items, containerId == InventoryID.WORN);
        if (containerId == InventoryID.INV)
        {
            inv = copy;
            if (firstInvThisTick == null) firstInvThisTick = copy;
        }
        else
        {
            worn = copy;
            if (firstWornThisTick == null) firstWornThisTick = copy;
        }
        if (pending != null && pending.account.equals(name) && pending.respawnTick >= 0)
        {
            if (tick == pending.respawnTick)
            {
                pending.eventOnRespawn = true;
                if (containerId == InventoryID.INV && pending.afterInv == null) pending.afterInv = copy;
                if (containerId == InventoryID.WORN && pending.afterWorn == null) pending.afterWorn = copy;
            }
            else if (tick > pending.respawnTick) pending.laterEvent = true;
        }
    }

    boolean seenDeath(String name, int tick)
    {
        switchAccount(name);
        if (name == null || name.isBlank() || Integer.valueOf(tick).equals(lastDeathTick.get(name))) return true;
        lastDeathTick.put(name, tick);
        return false;
    }

    void died(String name, String at, int tick, boolean itemsReadable)
    {
        switchAccount(name);
        Image beforeInv = inv, beforeWorn = worn;
        pending = itemsReadable && beforeInv != null && beforeInv.known
            ? new Pending(name, at, tick, beforeInv, beforeWorn == null ? image(null, true) : beforeWorn) : null;
    }

    void gameState(boolean loading, boolean loggedIn, int tick)
    {
        if (pending == null || tick <= pending.deathTick) return;
        if (loading) pending.loading = true;
        if (loggedIn && pending.loading) pending.loggedIn = true;
    }

    Result tick(String name, int tick, boolean loggedIn, int hitpoints, boolean itemsReadable)
    {
        switchAccount(name);
        Pending p = pending;
        if (p == null || !p.account.equals(name)) return null;
        if (!itemsReadable) return close();
        if (p.respawnTick < 0)
        {
            if (p.loggedIn && loggedIn && hitpoints > 0 && tick > p.deathTick)
            {
                p.respawnTick = tick;
                if (eventTick == tick)
                {
                    p.afterInv = firstInvThisTick; p.afterWorn = firstWornThisTick;
                    p.eventOnRespawn = p.afterInv != null || p.afterWorn != null;
                }
            }
            else if (loggedIn && tick - p.deathTick >= GIVE_UP_TICKS) return close();
            return null;
        }
        if (p.eventOnRespawn && tick >= p.respawnTick + SETTLE_TICKS)
        {
            Image afterInv = p.afterInv == null ? p.beforeInv : p.afterInv;
            Image afterWorn = p.afterWorn == null ? p.beforeWorn : p.afterWorn;
            pending = null;
            return new Result(name, p.at, afterInv.known && afterWorn.known ? diff(p.beforeInv, p.beforeWorn, afterInv, afterWorn) : null);
        }
        if (!p.eventOnRespawn && tick >= p.respawnTick + QUIET_TICKS)
        {
            pending = null;
            return new Result(name, p.at, p.laterEvent ? null : new Change(Collections.emptyList(), Collections.emptyList()));
        }
        return null;
    }

    private Result close()
    {
        Pending p = pending; pending = null;
        return p == null ? null : new Result(p.account, p.at, null);
    }

    static Change diff(Image beforeInv, Image beforeWorn, Image afterInv, Image afterWorn)
    {
        Map<Integer, Long> net = new TreeMap<>();
        for (Stack s : beforeInv.slots) net.merge(s.id, (long) s.quantity, Long::sum);
        for (Stack s : beforeWorn.slots) net.merge(s.id, (long) s.quantity, Long::sum);
        for (Stack s : afterInv.slots) net.merge(s.id, -(long) s.quantity, Long::sum);
        for (Stack s : afterWorn.slots) net.merge(s.id, -(long) s.quantity, Long::sum);
        List<Stack> left = new ArrayList<>(), appeared = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : net.entrySet())
        {
            long delta = entry.getValue();
            if (Math.abs(delta) > Integer.MAX_VALUE) return null; // an oversized result cannot be represented honestly
            if (delta > 0) left.add(new Stack(entry.getKey(), (int) delta));
            if (delta < 0) appeared.add(new Stack(entry.getKey(), (int) -delta));
        }
        if (left.size() > 42 || appeared.size() > 42) return null;
        return new Change(left, appeared);
    }

    private static Image image(Item[] items, boolean nullMeansEmpty)
    {
        if (items == null) return new Image(Collections.emptyList(), nullMeansEmpty);
        if (items.length > (nullMeansEmpty ? 14 : 28)) return new Image(Collections.emptyList(), false);
        List<Stack> copy = new ArrayList<>();
        try
        {
            for (Item item : items) if (item != null && item.getId() > 0)
            {
                if (item.getQuantity() <= 0) return new Image(Collections.emptyList(), false);
                copy.add(new Stack(item.getId(), item.getQuantity()));
            }
        }
        catch (RuntimeException unavailable) { return new Image(Collections.emptyList(), false); }
        return new Image(Collections.unmodifiableList(copy), true);
    }
}
