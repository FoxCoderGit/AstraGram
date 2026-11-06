package org.telegram.messenger;

import java.util.ArrayList;
import java.util.List;

/** Character-level differences with bounded memory for long message captions. */
public final class MessageTextDiff {
    public static final class Range {
        public final int start, end;
        Range(int start, int end) { this.start = start; this.end = end; }
    }
    public final List<Range> removed = new ArrayList<>();
    public final List<Range> added = new ArrayList<>();

    private MessageTextDiff() {}

    public static MessageTextDiff compare(String before, String after) {
        MessageTextDiff result = new MessageTextDiff();
        ArrayList<Range> a = tokenize(before), b = tokenize(after);
        if ((long) (a.size() + 1) * (b.size() + 1) > 1000000) {
            int prefix = 0, suffix = 0;
            while (prefix < a.size() && prefix < b.size() && equal(before, a.get(prefix), after, b.get(prefix))) prefix++;
            while (suffix < a.size() - prefix && suffix < b.size() - prefix && equal(before, a.get(a.size() - 1 - suffix), after, b.get(b.size() - 1 - suffix))) suffix++;
            for (int i = prefix; i < a.size() - suffix; i++) result.removed.add(a.get(i));
            for (int i = prefix; i < b.size() - suffix; i++) result.added.add(b.get(i));
            return result;
        }
        int[][] lengths = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                lengths[i][j] = equal(before, a.get(i), after, b.get(j)) ? 1 + lengths[i + 1][j + 1] : Math.max(lengths[i + 1][j], lengths[i][j + 1]);
            }
        }
        int i = 0, j = 0;
        while (i < a.size() || j < b.size()) {
            if (i < a.size() && j < b.size() && equal(before, a.get(i), after, b.get(j))) { i++; j++; }
            else if (i < a.size() && (j == b.size() || lengths[i + 1][j] >= lengths[i][j + 1])) result.removed.add(a.get(i++));
            else result.added.add(b.get(j++));
        }
        return result;
    }

    private static ArrayList<Range> tokenize(String text) {
        ArrayList<Range> result = new ArrayList<>();
        for (int index = 0; index < text.length();) {
            int next = index + Character.charCount(text.codePointAt(index));
            result.add(new Range(index, next));
            index = next;
        }
        return result;
    }
    private static boolean equal(String a, Range ar, String b, Range br) {
        return ar.end - ar.start == br.end - br.start && a.regionMatches(ar.start, b, br.start, ar.end - ar.start);
    }
}
