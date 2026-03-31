package com.limelight;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.util.Xml;

import androidx.preference.PreferenceManager;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class DefaultCustomizationSeeder {
    private static final String DEFAULTS_DIR = "defaults";
    private static final String DEFAULT_PREFS_ASSET = "default_preferences.xml";
    private static final String KEYBOARD_PREFS_ASSET = "OSC_Keyboard.xml";
    private static final String PROFILES_ASSET = "profiles.json";
    private static final String SEED_MARKER = "custom_defaults_seeded_v1";
    private static final String PROFILES_DIR = "profiles";
    private static final String PROFILES_FILE = "profiles.json";

    private DefaultCustomizationSeeder() {}

    static void seedIfNeeded(Context context) {
        SharedPreferences defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences keyboardPrefs = context.getSharedPreferences("OSC_Keyboard", Context.MODE_PRIVATE);
        File profilesFile = new File(new File(context.getFilesDir(), PROFILES_DIR), PROFILES_FILE);

        if (defaultPrefs.getBoolean(SEED_MARKER, false)) {
            return;
        }

        if (profilesFile.exists() || !keyboardPrefs.getAll().isEmpty() || defaultPrefs.contains("keyboard_axi_list")) {
            defaultPrefs.edit().putBoolean(SEED_MARKER, true).apply();
            return;
        }

        try {
            importSharedPreferencesXml(
                    context.getAssets(),
                    DEFAULTS_DIR + "/" + DEFAULT_PREFS_ASSET,
                    defaultPrefs.edit()
            );
            importSharedPreferencesXml(
                    context.getAssets(),
                    DEFAULTS_DIR + "/" + KEYBOARD_PREFS_ASSET,
                    keyboardPrefs.edit()
            );
            copyProfilesJson(context.getAssets(), profilesFile);
            defaultPrefs.edit().putBoolean(SEED_MARKER, true).apply();
        } catch (Exception e) {
            LimeLog.warning("DefaultCustomizationSeeder: Failed to seed defaults: " + e);
            e.printStackTrace();
        }
    }

    private static void copyProfilesJson(AssetManager assets, File outFile) throws IOException {
        File dir = outFile.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw new IOException("Failed to create profiles dir: " + dir);
        }

        try (InputStream in = assets.open(DEFAULTS_DIR + "/" + PROFILES_ASSET);
             FileOutputStream out = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    private static void importSharedPreferencesXml(
            AssetManager assets,
            String assetPath,
            SharedPreferences.Editor editor
    ) throws Exception {
        try (InputStream in = assets.open(assetPath)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, "utf-8");

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    String name = parser.getAttributeValue(null, "name");
                    if (name != null) {
                        switch (tag) {
                            case "string":
                                editor.putString(name, parser.nextText());
                                break;
                            case "boolean":
                                editor.putBoolean(name, parseBoolean(parser.getAttributeValue(null, "value")));
                                break;
                            case "int":
                                editor.putInt(name, Integer.parseInt(parser.getAttributeValue(null, "value")));
                                break;
                            case "long":
                                editor.putLong(name, Long.parseLong(parser.getAttributeValue(null, "value")));
                                break;
                            case "float":
                                editor.putFloat(name, Float.parseFloat(parser.getAttributeValue(null, "value")));
                                break;
                        }
                    }
                }
                eventType = parser.next();
            }
        }

        editor.apply();
    }

    private static boolean parseBoolean(String value) {
        return value != null && Boolean.parseBoolean(value);
    }
}
