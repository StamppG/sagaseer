package com.sagaseer;

import net.runelite.client.util.Text;

/**
 * Whether the looting bag window says it's empty. The game sends no container for an empty bag, so the window's own "The bag is
 * empty." text (drawn only for the bag itself, only when empty) is the only source; a half-drawn area is not empty.
 */
final class LootingBagWidgetState
{
    static final String EMPTY_TEXT = "The bag is empty.";

    private LootingBagWidgetState()
    {
    }

    /** Empty when the empty text shows and no child holds an item. */
    static boolean saysEmpty(String[] texts, int[] itemIds)
    {
        if (texts == null || itemIds == null)
        {
            return false;
        }
        for (int itemId : itemIds)
        {
            if (itemId > 0)
            {
                return false;
            }
        }
        for (String text : texts)
        {
            if (text != null && EMPTY_TEXT.equals(Text.removeTags(text).trim()))
            {
                return true;
            }
        }
        return false;
    }
}
