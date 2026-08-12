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

    @SerializedName("host")     public String host = "100.108.8.60";
    @SerializedName("port")     public int port = 47989;
    @SerializedName("httpsPort")public int httpsPort = 47984;
    @SerializedName("fps")      public int fps = 120;
    @SerializedName("width")    public int width = 2340;
    @SerializedName("height")   public int height = 1080;
    @SerializedName("bitrate")  public int bitrate = 40;
    @SerializedName("codec")    public String codec = "auto";
    @SerializedName("inputOnly")public boolean inputOnly = false;
    @SerializedName("audioEnabled") public boolean audioEnabled = true;
    @SerializedName("serverCertBase64") public String serverCertBase64 = null;
    @SerializedName("appId") public int appId = 0;
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
