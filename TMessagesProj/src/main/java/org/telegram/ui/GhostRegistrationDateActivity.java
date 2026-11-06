package org.telegram.ui;

import android.view.View;
import org.telegram.messenger.GhostRegistrationDateStore;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import java.util.ArrayList;

public class GhostRegistrationDateActivity extends UniversalFragment {
    public GhostRegistrationDateActivity(int account) { currentAccount = account; }
    @Override protected CharSequence getTitle() { return LocaleController.getString(R.string.GhostRegistrationDateDatabase); }
    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asButton(2, LocaleController.getString(R.string.GhostRegistrationDateSamples), Integer.toString(GhostRegistrationDateStore.count())));
        items.add(UItem.asButton(3, LocaleController.getString(R.string.GhostRegistrationDateRanges), Integer.toString(GhostRegistrationDateStore.ranges())));
        items.add(UItem.asShadow(LocaleController.getString(R.string.GhostRegistrationDateLocalInfo)));
    }
    @Override protected void onClick(UItem item, View view, int position, float x, float y) {}
    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }
}
