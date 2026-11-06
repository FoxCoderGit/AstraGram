package org.telegram.messenger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Hints only: every candidate must be resolved and checked against the requested ID. */
public final class GhostProfileLookupParser {
    private static final Pattern USERNAME = Pattern.compile("(?i)(?:(?<![a-z0-9_@])@|(?:https?://)?(?:www\\.)?(?:t\\.me|telegram\\.me)/)([a-z][a-z0-9_]{3,31})(?![a-z0-9_])");
    private static final Pattern FIELD = Pattern.compile("(?im)^\\s*(?:username|юзернейм|ник)\\s*[:=]\\s*@?([a-z][a-z0-9_]{3,31})(?![a-z0-9_])");
    private GhostProfileLookupParser() { }
    public static ArrayList<String> usernames(String text) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String input = text == null ? "" : text.substring(0, Math.min(text.length(), 32768));
        for (Pattern pattern : new Pattern[]{USERNAME, FIELD}) {
            Matcher matcher = pattern.matcher(input);
            while (matcher.find() && result.size() < 6) {
                String name = matcher.group(1).toLowerCase(Locale.ROOT);
                if (!name.equals("usinfobot") && !name.equals("share") && !name.equals("joinchat")) result.add(name);
            }
        }
        return new ArrayList<>(result);
    }
}
