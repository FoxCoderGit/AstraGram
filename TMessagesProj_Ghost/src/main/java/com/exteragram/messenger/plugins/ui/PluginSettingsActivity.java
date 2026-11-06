package com.exteragram.messenger.plugins.ui;

import com.exteragram.messenger.plugins.Plugin;

import org.telegram.ui.GhostPluginSettingsActivity;

/**
 * Binary-compatible entry point used by ExteraGram plugins when they open
 * their settings from an error action. The actual renderer is GhostGram's
 * settings screen, backed by the same Python plugin state.
 */
public class PluginSettingsActivity extends GhostPluginSettingsActivity {
    public PluginSettingsActivity(Plugin plugin) {
        super(plugin.getId(), plugin.getName());
    }
}
