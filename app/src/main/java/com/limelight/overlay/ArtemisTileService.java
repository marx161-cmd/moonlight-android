package com.limelight.overlay;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

// Show/hide is now Pixel Quick Tap's job (see cybersyn-quicktap/), so this tile no
// longer toggles the overlay. It's a momentary action tile: opens the daemon's quick
// menu (ArtemisMenu, same slot as the real app's back-swipe popup) so special
// keys/keyboard-toggle/landscape/disconnect stay reachable without Quick Tap.
public class ArtemisTileService extends TileService {

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        getQsTile().setState(Tile.STATE_INACTIVE);
        getQsTile().updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();

        Intent intent = new Intent(this, ArtemisDaemonService.class);
        intent.setAction(ArtemisDaemonService.ACTION_SHOW_MENU);

        try {
            startForegroundService(intent);
        } catch (Exception e) {
            startService(intent);
        }

        // Momentary action, not a persistent on/off state -- always inactive.
        Tile tile = getQsTile();
        tile.setState(Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
