package com.sagaseer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sagaseer.ChangeAwareSourceCapture.Observation;
import java.time.Instant;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

/**
 * The state.grandExchange rows: the player's own GE slots with an offer, as the game reports them. Read only after the game has
 * sent the slots this login, so a list not sent yet never reads as "no offers".
 */
final class GrandExchangeOffers
{
    private GrandExchangeOffers() { }

    /**
     * Only events while logged in are the server's: the client fires EMPTY for every slot while logging in (RuneLite's GE plugin
     * ignores those too).
     */
    static boolean fromServer(GameState state)
    {
        return state == GameState.LOGGED_IN;
    }

    /** `sent`: the game has sent the slots since this login. */
    static Observation read(GrandExchangeOffer[] offers, boolean sent, IntFunction<String> names, Supplier<Instant> clock)
    {
        if (!sent || offers == null || names == null || clock == null) return Observation.notRead();
        try
        {
            JsonArray rows = new JsonArray();
            for (int slot = 0; slot < offers.length; slot++)
            {
                GrandExchangeOffer offer = offers[slot];
                if (offer == null || offer.getState() == null || offer.getState() == GrandExchangeOfferState.EMPTY) continue;
                String name = names.apply(offer.getItemId());
                if (name == null || name.isEmpty()) return Observation.unavailable();
                JsonObject row = new JsonObject();
                row.addProperty("slot", slot + 1);
                row.addProperty("state", offer.getState().name());
                row.addProperty("itemId", offer.getItemId());
                row.addProperty("name", name);
                row.addProperty("quantity", offer.getTotalQuantity());
                row.addProperty("done", offer.getQuantitySold());
                row.addProperty("price", offer.getPrice());
                row.addProperty("spent", offer.getSpent());
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
