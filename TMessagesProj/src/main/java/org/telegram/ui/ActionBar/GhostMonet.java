package org.telegram.ui.ActionBar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import androidx.core.graphics.ColorUtils;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.monet.blend.Blend;
import org.telegram.ui.ActionBar.monet.hct.Hct;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves the palette expressions used by exteraGram's extracted Monet themes. */
public final class GhostMonet {
    private static final Pattern EXPRESSION = Pattern.compile("^([an][123])_(\\d+)|^(mBlack|mWhite|mRed(?:200|500|800)|mGreen(?:200|500|800))$");
    private static final Pattern PARAMETERS = Pattern.compile("^([^()]+)(?:\\(([^()]+)\\))?$");
    private static boolean registered;
    private GhostMonet() { }

    public static boolean isAsset(String asset) {
        return asset != null && asset.startsWith("ghost_monet_") && asset.endsWith(".attheme");
    }

    public static int color(String expression) {
        String value = expression.trim();
        if (value.endsWith("h")) {
            return harmonize(Integer.parseInt(value.substring(0, value.length() - 1)));
        }
        if (value.matches("-?\\d+")) return Integer.parseInt(value);
        Matcher parameters = PARAMETERS.matcher(value);
        if (!parameters.matches()) throw new IllegalArgumentException("Invalid Monet expression: " + value);
        String token = parameters.group(1).trim();
        Matcher match = EXPRESSION.matcher(token);
        if (!match.matches()) throw new IllegalArgumentException("Unknown Monet token: " + token);
        int color;
        if (match.group(1) != null) {
            String palette = match.group(1);
            int tone = Integer.parseInt(match.group(2));
            color = paletteColor(palette, tone);
        } else {
            switch (token) {
                case "mBlack": color = Color.BLACK; break;
                case "mWhite": color = Color.WHITE; break;
                case "mRed200": color = 0xffef9a9a; break;
                case "mRed500": color = 0xfff44336; break;
                case "mRed800": color = 0xffc62828; break;
                case "mGreen200": color = 0xffa5d6a7; break;
                case "mGreen500": color = 0xff4caf50; break;
                case "mGreen800": color = 0xff2e7d32; break;
                default: throw new IllegalArgumentException(token);
            }
        }
        int alpha = 100, saturation = 100, lightness = 100;
        if (parameters.group(2) != null) {
            for (String option : parameters.group(2).split(",")) {
                String[] pair = option.trim().split("=");
                if (pair.length != 2) throw new IllegalArgumentException(option);
                int percent = Integer.parseInt(pair[1].trim());
                if (percent < 0 || percent > 100) throw new IllegalArgumentException(option);
                switch (pair[0].trim()) {
                    case "a": alpha = percent; break;
                    case "s": saturation = percent; break;
                    case "l": lightness = percent; break;
                    default: throw new IllegalArgumentException(option);
                }
            }
        }
        if (saturation != 100) color = ColorUtils.blendARGB(Color.WHITE, color, saturation / 100f);
        if (lightness != 100) color = ColorUtils.blendARGB(Color.BLACK, color, lightness / 100f);
        if (alpha != 100) color = ColorUtils.setAlphaComponent(color, (int) (alpha * 2.55f));
        if (token.startsWith("mRed") || token.startsWith("mGreen")) color = harmonize(color);
        return color;
    }

    private static int paletteColor(String palette, int shade) {
        if (Build.VERSION.SDK_INT >= 31) {
            String name = "system_" + (palette.charAt(0) == 'a' ? "accent" : "neutral") + palette.charAt(1) + "_" + shade;
            int resource = ApplicationLoader.applicationContext.getResources().getIdentifier(name, "color", "android");
            if (resource != 0) return ApplicationLoader.applicationContext.getColor(resource);
        }
        // Material tonal palettes from a Telegram-blue seed for Android 7–11.
        Hct seed = Hct.fromInt(0xff3390ec);
        double hue = seed.getHue(), chroma;
        switch (palette) {
            case "a1": chroma = Math.max(48, seed.getChroma()); break;
            case "a2": chroma = 16; break;
            case "a3": hue = (hue + 60) % 360; chroma = 24; break;
            case "n1": chroma = 4; break;
            case "n2": chroma = 8; break;
            default: throw new IllegalArgumentException(palette);
        }
        return Hct.from(hue, chroma, 100 - shade / 10.0).toInt();
    }

    private static int harmonize(int color) {
        return Build.VERSION.SDK_INT >= 31 ? Blend.harmonize(color, paletteColor("a1", 600)) : color;
    }

    public static void registerPaletteChanges() {
        if (registered || Build.VERSION.SDK_INT < 31) return;
        IntentFilter filter = new IntentFilter("android.intent.action.OVERLAY_CHANGED");
        filter.addDataScheme("package");
        filter.addDataSchemeSpecificPart("android", 0);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                AndroidUtilities.runOnUIThread(() -> {
                    Theme.refreshGhostMonetPreviews();
                    Theme.ThemeInfo active = Theme.getActiveTheme();
                    if (active != null && isAsset(active.assetName)) Theme.applyTheme(active, Theme.isCurrentThemeNight());
                });
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 33) ApplicationLoader.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            else ApplicationLoader.applicationContext.registerReceiver(receiver, filter);
            registered = true;
        } catch (Exception error) { FileLog.e(error); }
    }
}
