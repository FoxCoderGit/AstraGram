package org.telegram.ui;

import android.os.Bundle;
import org.telegram.messenger.GhostChatSettings;
import org.telegram.ui.ActionBar.BaseFragment;

public final class GhostNavigation {
    private GhostNavigation(){ }
    public static BaseFragment root(Bundle args){return GhostChatSettings.legacy()?new DialogsActivity(args):new MainTabsActivity();}
}
