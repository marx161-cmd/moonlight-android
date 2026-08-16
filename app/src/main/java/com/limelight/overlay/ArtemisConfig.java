package com.limelight.overlay;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.File;
import java.io.IOException;

public class ArtemisConfig {

    private static final String CONFIG_PATH =
            "/data/data/com.termux/files/home/.config/artemis/artemis.conf";

    @SerializedName("host")     public String host = "192.168.0.209";
    @SerializedName("port")     public int port = 47989;
    @SerializedName("httpsPort")public int httpsPort = 47984;
    @SerializedName("fps")      public int fps = 120;
    @SerializedName("width")    public int width = 2340;
    @SerializedName("height")   public int height = 1080;
    @SerializedName("bitrate")  public int bitrate = 40;
    @SerializedName("codec")    public String codec = "auto";
    @SerializedName("inputOnly")public boolean inputOnly = false;
    // Requests a landscape-shaped resolution from Apollo/Sunshine (which negotiates its
    // virtual display to match per-connect, per linux_virtual_display_auto) while the
    // overlay window itself stays sized to the phone's real (portrait) geometry. The
    // landscape video buffer is letterboxed into that portrait window via
    // SurfaceHolder.setFixedSize -- no window move/resize needed. Toggled at runtime by
    // GameMenu's "rotate screen" option.
    @SerializedName("landscape") public boolean landscape = false;
    // ArtemisMenu's HUD toggle -- stats readout, ported from Game's performanceOverlay.
    @SerializedName("showHud") public boolean showHud = false;
    // Audio never plays on-device via Sunshine; leaving it off stops the host
    // from encoding+sending an audio stream we'd only discard.
    @SerializedName("audioEnabled") public boolean audioEnabled = false;
    @SerializedName("serverCertBase64") public String serverCertBase64 = null;
    // Apollo's numeric app id, NOT arbitrary: CRC32("Desktop" + sha256(desktop.png)),
    // computed from apps.json's single "Desktop" entry (calculate_app_id() in Apollo's
    // process.cpp). 0 (StreamConfiguration.INVALID_APP_ID) used to sit here -- Game.java
    // aborts outright on that value and never sends it, so it was daemon-only wire
    // behavior the host had never really been exercised against: negotiation "succeeded"
    // but sessions failed with Initial Ping Timeout / a host-side session::join watchdog
    // trap under load. Stable as long as apps.json stays single-app (no index-collision
    // fallback to recompute).
    @SerializedName("appId") public int appId = 881448767;
    @SerializedName("appName") public String appName = "Desktop";
    @SerializedName("appUuid") public String appUuid = "D35489BC-EF06-D101-D003-1F39FECBE5CD";

    public static ArtemisConfig load() {
        try {
            File f = new File(CONFIG_PATH);
            if (f.exists()) {
                return new Gson().fromJson(new FileReader(f), ArtemisConfig.class);
            }
        } catch (IOException e) {
            Log.e("ArtemisConfig", "Failed to load config", e);
        }
        ArtemisConfig defaults = new ArtemisConfig();
        defaults.save();
        return defaults;
    }

    public void save() {
        try {
            new File(CONFIG_PATH).getParentFile().mkdirs();
            new FileWriter(CONFIG_PATH).append(new Gson().toJson(this)).close();
        } catch (IOException e) {
            Log.e("ArtemisConfig", "Failed to save config", e);
        }
    }
}
