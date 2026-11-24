package com.exteragram.messenger.plugins;

import com.chaquo.python.PyObject;
import org.telegram.messenger.Utilities;
import java.io.File;
import java.util.Locale;

/** Access to the Python plugin instances used by both package formats. */
public final class PythonPluginsEngine {
    private final PluginsController controller;
    private final boolean structured;
    public final Instances pluginInstances = new Instances();

    PythonPluginsEngine(PluginsController controller, boolean structured) {
        this.controller = controller;
        this.structured = structured;
    }

    public boolean isPlugin(File file, Object context) {
        if (file == null) return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        return structured ? name.endsWith(".elyx") || name.endsWith(".eaf")
                || name.endsWith(".elyx.zip") || name.endsWith(".eaf.zip") : name.endsWith(".plugin");
    }

    public void setPluginEnabled(String id, boolean enabled, Utilities.Callback<String> callback) {
        controller.setPluginEnabled(id, enabled, callback);
    }

    public final class Instances {
        public PyObject get(String id) {
            return controller.manager().get("_plugins").callAttr("get", id);
        }
    }
}
