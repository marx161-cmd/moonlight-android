package com.limelight.overlay;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

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
        intent.setAction(ArtemisDaemonService.ACTION_TOGGLE);

        try {
            startForegroundService(intent);
        } catch (Exception e) {
            startService(intent);
        }

        Tile tile = getQsTile();
        tile.setState(tile.getState() == Tile.STATE_ACTIVE ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
        tile.updateTile();
    }
}
