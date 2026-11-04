package com.sagaseer;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.sagaseer.ChangeAwareSourceCapture.Observation;
import java.time.Instant;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;

/** Reads the inventory or worn equipment. A same-session container event must come first, so a read is ready. */
final class InventorySource
{
    private final int size;
    private String readyAccount;

    InventorySource()
    {
        this(28);
    }

    InventorySource(int size)
    {
        this.size = size;
    }

    void containerEvent(String account)
    {
        readyAccount = account == null || account.isBlank() ? null : account;
    }

    void reset()
    {
        readyAccount = null;
    }

    Observation read(String account, ItemContainer container,
                     IntFunction<String> names, Supplier<Instant> completedAt)
    {
        if (account == null || !account.equals(readyAccount)) return Observation.notRead();
        if (container == null || names == null || completedAt == null)
            return Observation.unavailable();
        // Take one container image on the client thread.
        try
        {
            return readItems(account, container.getItems(), names, completedAt);
        }
        catch (RuntimeException unavailable)
        {
            return Observation.unavailable();
        }
    }

    /**
     * The game sends no equipment container while every slot is empty, so no change event ever comes. Read after login settles, a
     * missing container is fourteen empty slots.
     */
    Observation readWorn(String account, ItemContainer container, IntFunction<String> names, Supplier<Instant> completedAt)
    {
        if (container != null || account == null || account.isBlank() || names == null || completedAt == null)
            return read(account, container, names, completedAt);
        readyAccount = account;
        return readSlots(account, index -> null, names, completedAt);
    }

    Observation readItems(String account, Item[] items,
                          IntFunction<String> names, Supplier<Instant> completedAt)
    {
        if (account == null || !account.equals(readyAccount)) return Observation.notRead();
        // RuneLite trims trailing empty slots and marks empty ones -1: both are empty, not unknown.
        if (items == null || items.length > size || names == null || completedAt == null)
            return Observation.unavailable();
        return readSlots(account, index -> index < items.length ? items[index] : null, names, completedAt);
    }

    private Observation readSlots(String account, IntFunction<Item> itemAt,
                                  IntFunction<String> names, Supplier<Instant> completedAt)
    {
        if (account == null || !account.equals(readyAccount)) return Observation.notRead();
        JsonArray slots = new JsonArray();
        for (int index = 0; index < size; index++)
        {
            Item item;
            try
            {
                item = itemAt.apply(index);
            }
            catch (RuntimeException unavailable)
            {
                return Observation.unavailable();
            }
            if (item == null)
            {
                slots.add(JsonNull.INSTANCE);
                continue;
            }
            int id;
            int quantity;
            try
            {
                id = item.getId();
                quantity = item.getQuantity();
            }
            catch (RuntimeException unavailable)
            {
                return Observation.unavailable();
            }
            if (id <= 0)
            {
                slots.add(JsonNull.INSTANCE); // RuneLite's empty slot (id -1)
                continue;
            }
            if (quantity <= 0)
            {
                return Observation.unavailable();
            }
            String name;
            try
            {
                name = names.apply(id);
            }
            catch (RuntimeException unavailable)
            {
                return Observation.unavailable();
            }
            if (name == null || name.isEmpty() || name.length() > 100 || !wellFormedUnicode(name))
            {
                return Observation.unavailable();
            }
            JsonObject slot = new JsonObject();
            slot.addProperty("slot", index);
            slot.addProperty("id", id);
            slot.addProperty("quantity", quantity);
            slot.addProperty("name", name);
            slots.add(slot);
        }
        try
        {
            Instant readCompletedAt = completedAt.get();
            if (readCompletedAt == null) return Observation.unavailable();
            return new Observation(slots, ChangeAwareSourceCapture.time(readCompletedAt), null);
        }
        catch (RuntimeException unavailable)
        {
            return Observation.unavailable();
        }
    }

    private static boolean wellFormedUnicode(String value)
    {
        for (int i = 0; i < value.length(); i++)
        {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c))
            {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    return false;
            }
            else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }
}
