#!/system/bin/sh
# Toggle the artemisd streaming overlay (show <-> hide).
# Wired to Pixel Quick Tap via Cybersyn profile QuickTapSidebarTrigger.
am start-service -n com.termux.diana.root.noir/com.limelight.overlay.ArtemisDaemonService \
  -a com.termux.diana.action.OVERLAY_TOGGLE
