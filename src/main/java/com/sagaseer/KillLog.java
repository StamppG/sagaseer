package com.sagaseer;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.sagaseer.ChangeAwareSourceCapture.Observation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.util.Text;

/** The game's Slayer Kill Log: a complete read is held until this session ends. */
final class KillLog
{
    static final Pattern SUPERIORS_LINE = Pattern.compile("^Superior creatures: ([0-9][0-9,]{0,12})$");
    private static final int MAX_TASKS = 200;
    private boolean open;
    private JsonArray complete;
    private String completeAt;
    private String completeAccount;

    void opened() { open = true; }
    void closed() { open = false; }
    boolean open() { return open; }

    /** Row i is child i in all three columns. A partial layout sends nothing. */
    boolean tick(String account, IntFunction<Widget> component, Supplier<Instant> clock)
    {
        if (!open || account == null || account.isBlank()) return false;
        Widget names = component.apply(InterfaceID.KillLog.NAME);
        Widget kills = component.apply(InterfaceID.KillLog.KILL);
        Widget streaks = component.apply(InterfaceID.KillLog.STREAK);
        Widget contents = component.apply(InterfaceID.KillLog.CONTENTS);
        Widget title = component.apply(InterfaceID.KillLog.INTERFACE_TITLE);
        List<String> n = column(names), k = column(kills), s = column(streaks);
        Integer superiors = superiors(contents);
        if (superiors == null) superiors = superiors(title);
        return accept(account, n, k, s, superiors, clock);
    }

    /** Kept separate from widgets so row alignment can be tested without game UI or reflection. */
    boolean accept(String account, List<String> n, List<String> k, List<String> s, Integer superiors, Supplier<Instant> clock)
    {
        if (!open || account == null || account.isBlank()) return false;
        if (n.isEmpty() || n.size() > MAX_TASKS || k.size() != n.size() || s.size() != n.size()) return false;
        JsonArray tasks = new JsonArray();
        for (int i = 0; i < n.size(); i++)
        {
            Integer count = number(k.get(i));
            Integer streak = number(s.get(i));
            if (n.get(i).isBlank() || n.get(i).length() > 60 || count == null || streak == null) return false;
            JsonObject row = new JsonObject();
            row.addProperty("name", n.get(i));
            row.addProperty("kills", count);
            row.addProperty("streak", streak);
            tasks.add(row);
        }
        JsonObject row = new JsonObject();
        row.add("tasks", tasks);
        if (superiors == null) row.add("superiors", JsonNull.INSTANCE); else row.addProperty("superiors", superiors);
        JsonArray value = new JsonArray();
        value.add(row);
        if (account.equals(completeAccount) && value.equals(complete)) return false;
        complete = value;
        completeAccount = account;
        completeAt = ChangeAwareSourceCapture.time(clock.get());
        return true;
    }

    private static List<String> column(Widget root)
    {
        List<String> out = new ArrayList<>();
        Widget[] children = root == null ? null : root.getDynamicChildren();
        if (children == null || children.length == 0) children = root == null ? null : root.getChildren();
        if (children == null) return out;
        for (Widget child : children)
            if (child != null && child.getType() == WidgetType.TEXT) out.add(clean(child.getText()));
        return out;
    }

    private static Integer number(String text)
    {
        if (!text.matches("[0-9][0-9,]{0,12}")) return null;
        try { return Integer.valueOf(text.replace(",", "")); }
        catch (NumberFormatException invalid) { return null; }
    }

    private static Integer superiors(Widget root)
    {
        if (root == null) return null;
        if (root.getType() == WidgetType.TEXT)
        {
            Matcher line = SUPERIORS_LINE.matcher(clean(root.getText()));
            if (line.matches()) return number(line.group(1));
        }
        Widget[] children = root.getDynamicChildren();
        if (children == null || children.length == 0) children = root.getChildren();
        if (children != null) for (Widget child : children)
        {
            Integer found = superiors(child);
            if (found != null) return found;
        }
        return null;
    }

    private static String clean(String text) { return text == null ? "" : Text.removeTags(text).trim(); }

    Observation observation(String account)
    {
        if (complete == null || account == null || !account.equals(completeAccount)) return Observation.notRead();
        return new Observation(complete.deepCopy(), completeAt);
    }

    void reset()
    {
        open = false;
        complete = null;
        completeAt = null;
        completeAccount = null;
    }
}
