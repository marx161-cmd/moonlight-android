package com.limelight.overlay;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

// Separate from ArtemisTileService (which pops the quick menu). This tile is a real
// on/off toggle for ArtemisConfig.stripMode: does Quick Tap's next OVERLAY_SHOW draw
// the full kiosk overlay, or a view-only top slice (see ArtemisOverlayWindow.show()).
// Deliberately does NOT poke the daemon service if it's already running -- starting
// it just to reshape an existing overlay risks a stray always-created-invisible
// window flashing on screen (ArtemisDaemonService.onStartCommand always constructs
// ArtemisOverlayWindow). The new mode just takes effect on the next show.
public class ArtemisStripModeTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        syncTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        ArtemisConfig config = ArtemisConfig.load();
        config.stripMode = !config.stripMode;
        config.save();
        syncTile();
    }

    private void syncTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        ArtemisConfig config = ArtemisConfig.load();
        tile.setState(config.stripMode ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(config.stripMode ? "Artemis: Strip" : "Artemis: Full");
        tile.updateTile();
    }
}
