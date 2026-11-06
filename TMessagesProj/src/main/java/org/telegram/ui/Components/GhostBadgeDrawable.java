package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.GhostBadgeRegistry;
import org.telegram.messenger.R;

/** Native vectors imported unchanged from the owner's single-frame TGS files. */
public final class GhostBadgeDrawable extends Drawable {
    public final long key;
    private final Drawable artwork;
    public GhostBadgeDrawable(long key) {
        this.key=key;
        int resource=key==GhostBadgeRegistry.DEV?R.drawable.veyl_badge_creator:R.drawable.veyl_badge_supporter;
        artwork=ApplicationLoader.applicationContext.getResources().getDrawable(resource).mutate();
    }
    @Override public void draw(Canvas canvas) { artwork.setBounds(getBounds());artwork.draw(canvas); }
    @Override public void setAlpha(int alpha) { artwork.setAlpha(alpha);invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { artwork.setColorFilter(filter);invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    // The surrounding status slot determines size: dialogs, author names and profile headers differ.
    @Override public int getIntrinsicWidth() { return -1; }
    @Override public int getIntrinsicHeight() { return -1; }
}
