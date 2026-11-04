package com.sagaseer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.StructComposition;
import lombok.extern.slf4j.Slf4j;

/**
 * The log's tabs, pages and item order from the cache (enum 2102 lists tab structs; params 682/683 name a tab and its pages,
 * 689/690 a page and its items). Enum order is the game's order; any surprise makes the layout unreadable.
 */
@Slf4j
final class CollectionLogLayout
{
    private static final AtomicBoolean CAP_LOGGED = new AtomicBoolean();
    private static final int TABS_ENUM = 2102;
    private static final int TAB_NAME = 682;
    private static final int TAB_PAGES_ENUM = 683;
    private static final int PAGE_NAME = 689;
    private static final int PAGE_ITEMS_ENUM = 690;

    private static final Set<String> TABS = Set.of("Bosses", "Raids", "Clues", "Minigames", "Other");
    private static final int MAX_PAGES = 300;
    private static final int MAX_ITEMS_PER_PAGE = 200;
    static final int MAX_TOTAL_ITEMS = 4_000;
    private static final int MAX_NAME = 100;

    static final class Page
    {
        final String tab;
        /** 0-based position within the tab, in the game's order. */
        final int order;
        final String name;
        final int[] itemIds;
        final String[] itemNames;

        private Page(String tab, int order, String name, int[] itemIds, String[] itemNames)
        {
            this.tab = tab;
            this.order = order;
            this.name = name;
            this.itemIds = itemIds;
            this.itemNames = itemNames;
        }
    }

    final List<Page> pages;

    private CollectionLogLayout(List<Page> pages)
    {
        this.pages = Collections.unmodifiableList(pages);
    }

    /** Client thread. Null if the layout cannot be read. */
    static CollectionLogLayout read(Client client)
    {
        return read(client::getEnum, client::getStructComposition,
            id -> SagaSeerPlugin.plainItemName(client.getItemDefinition(id).getName()));
    }

    static CollectionLogLayout read(IntFunction<EnumComposition> enums,
        IntFunction<StructComposition> structs, IntFunction<String> itemNames)
    {
        try
        {
            List<Page> pages = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int totalItems = 0;
            EnumComposition tabs = enums.apply(TABS_ENUM);
            if (tabs == null || tabs.size() < 1 || tabs.size() > TABS.size()) return null;
            for (int tabStruct : tabs.getIntVals())
            {
                StructComposition tab = structs.apply(tabStruct);
                String tabName = tab.getStringValue(TAB_NAME);
                if (!TABS.contains(tabName)) return null;
                EnumComposition tabPages = enums.apply(tab.getIntValue(TAB_PAGES_ENUM));
                if (tabPages == null || tabPages.size() == 0) return null;
                int order = 0;
                for (int pageStruct : tabPages.getIntVals())
                {
                    StructComposition page = structs.apply(pageStruct);
                    String name = page.getStringValue(PAGE_NAME);
                    EnumComposition items = enums.apply(page.getIntValue(PAGE_ITEMS_ENUM));
                    if (name == null || name.isEmpty() || name.length() > MAX_NAME ||
                        !seen.add(name) || items == null || items.size() < 1 ||
                        items.size() > MAX_ITEMS_PER_PAGE || pages.size() == MAX_PAGES)
                        return null;
                    totalItems += items.size();
                    if (totalItems > MAX_TOTAL_ITEMS)
                    {
                        if (CAP_LOGGED.compareAndSet(false, true))
                            log.debug("SagaSeer Collection Log layout exceeds the 4,000-item cap");
                        return null;
                    }
                    int[] ids = items.getIntVals().clone();
                    String[] names = new String[ids.length];
                    for (int i = 0; i < ids.length; i++)
                    {
                        if (ids[i] < 1) return null;
                        names[i] = itemName(itemNames.apply(ids[i]), ids[i]);
                    }
                    pages.add(new Page(tabName, order++, name, ids, names));
                }
            }
            return pages.isEmpty() ? null : new CollectionLogLayout(pages);
        }
        catch (RuntimeException unreadable)
        {
            return null;
        }
    }

    /** Non-empty and at most 100 characters, as the server needs. */
    static String itemName(String name, int itemId)
    {
        if (name == null || name.isBlank()) return "Item " + itemId;
        return name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
    }
}
