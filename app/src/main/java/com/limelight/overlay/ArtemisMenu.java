package com.limelight.overlay;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import com.limelight.GameMenu;
import com.limelight.R;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.utils.KeyConfigHelper;
import com.limelight.utils.KeyMapper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * The daemon-overlay equivalent of {@link GameMenu} -- same AlertDialog-list structure and
 * the same special-keys table, wired to the Service instead of the Game Activity. Deliberately
 * NOT a literal reuse of GameMenu: that class is hard-typed to {@code Game} and half its options
 * (HUD/floating-button/virtual-controller/mouse-mode-select/touch-sensitivity/zoom/external
 * display) are Game's own on-screen touch-control overlay -- never ported into the daemon, and
 * would NPE here. Also omitted for now: quit-session/clipboard-sync/server-cmd, which all go
 * through Game's separate {@code httpConn} (NvHTTP) client that the daemon never builds --
 * {@link StreamController} only wires the low-latency NvConnection. Everything kept here is
 * NvConnection-only and faithfully portable.
 */
public class ArtemisMenu {

    public interface Host {
        /** Ends the daemon entirely (stream + overlay), same as OVERLAY_STOP. */
        void onDisconnect();
        void onToggleKeyboard();
        /** GameMenu's "rotate screen" slot -- see ArtemisDaemonService.toggleLandscape(). */
        void onToggleLandscape();
        /** Same down/up/modifier sequencing as Game.sendKeys(). */
        void onSendKeys(short[] keys);
        /** GameInputController state (shared input stack), not Game's UI -- portable as-is. */
        void onApplyMouseMode(int index);
        void onToggleHud();
        boolean isHudEnabled();
    }

    private final Context dialogContext;
    private final Host host;
    private AlertDialog currentDialog;

    public ArtemisMenu(Context serviceContext, Host host) {
        this.dialogContext = serviceContext;
        this.host = host;
    }

    private static class MenuOption {
        final String label;
        final Runnable runnable;
        MenuOption(String label, Runnable runnable) { this.label = label; this.runnable = runnable; }
    }

    private String s(int id) { return dialogContext.getString(id); }

    private void showMenuDialog(String title, MenuOption[] options) {
        int themeResId = dialogContext.getApplicationInfo().theme;
        Context themedContext = new ContextThemeWrapper(dialogContext, themeResId);
        AlertDialog.Builder builder = new AlertDialog.Builder(themedContext);
        builder.setTitle(title);

        ArrayAdapter<String> actions = new ArrayAdapter<>(themedContext, android.R.layout.simple_list_item_1);
        for (MenuOption option : options) actions.add(option.label);

        builder.setAdapter(actions, (dialog, which) -> {
            MenuOption option = options[which];
            if (option.runnable != null) option.runnable.run();
        });
        builder.setOnCancelListener(dialog -> hideMenu());

        if (currentDialog != null) currentDialog.dismiss();
        currentDialog = builder.create();

        // A Service has no window token of its own -- the dialog needs an overlay window
        // type (same permission the daemon already holds for its stream window) or it
        // can't be added at all. Must be set before show().
        Window window = currentDialog.getWindow();
        if (window != null) {
            window.setType(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        }
        currentDialog.show();
    }

    private void showSpecialKeysMenu() {
        List<MenuOption> options = new ArrayList<>();

        options.add(new MenuOption(s(R.string.game_menu_send_keys_esc),
                () -> sendKeys(new short[]{KeyboardTranslator.VK_ESCAPE})));
        options.add(new MenuOption(s(R.string.game_menu_send_keys_f11),
                () -> sendKeys(new short[]{KeyboardTranslator.VK_F11})));
        options.add(new MenuOption(s(R.string.game_menu_send_keys_alt_f4),
                () -> sendKeys(new short[]{KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_F4})));
        options.add(new MenuOption(s(R.string.game_menu_send_keys_alt_enter),
                () -> sendKeys(new short[]{KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_RETURN})));
        options.add(new MenuOption(s(R.string.game_menu_send_keys_ctrl_v),
                () -> sendKeys(new short[]{KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_V})));
        options.add(new MenuOption(s(R.string.game_menu_send_keys_win),
                () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN})));
        options.add(new MenuOption(s(R.string.game_menu_send_keys_win_d),
                () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_D})));
        options.add(new MenuOption(s(R.string.game_menu_send_keys_win_g),
                () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_G})));
        options.add(new MenuOption(s(R.string.game_menu_send_keys_ctrl_alt_tab),
                () -> sendKeys(new short[]{KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_TAB})));
        options.add(new MenuOption(s(R.string.game_menu_send_keys_shift_tab),
                () -> sendKeys(new short[]{KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_TAB})));
        options.add(new MenuOption(s(R.string.game_menu_send_keys_win_shift_left),
                () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_LEFT})));
        options.add(new MenuOption(s(R.string.game_menu_send_keys_ctrl_alt_shift_f1),
                () -> sendKeys(new short[]{KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_F1})));
        options.add(new MenuOption(s(R.string.game_menu_send_keys_ctrl_alt_shift_f12),
                () -> sendKeys(new short[]{KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_F12})));
        options.add(new MenuOption(s(R.string.game_menu_send_keys_alt_b),
                () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_B})));
        options.add(new MenuOption("i3: Terminal (Win+Enter)",
                () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_RETURN})));
        options.add(new MenuOption("i3: App Menu (Win+D)",
                () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_D})));
        options.add(new MenuOption("i3: Quit Session (Win+Shift+E)",
                () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_LSHIFT, (short) KeyMapper.VK_E})));
        options.add(new MenuOption("i3: Kill Window (Win+Shift+Q)",
                () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_Q})));
        options.add(new MenuOption("i3: Focus Left (Win+H)",
                () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, (short) KeyMapper.VK_H})));
        options.add(new MenuOption("i3: Focus Down (Win+J)",
                () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, (short) KeyMapper.VK_J})));
        options.add(new MenuOption("i3: Focus Up (Win+K)",
                () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, (short) KeyMapper.VK_K})));
        options.add(new MenuOption("i3: Focus Right (Win+L)",
                () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, (short) KeyMapper.VK_L})));

        // Same custom-shortcut import GameMenu supports -- fully portable (just reads a
        // JSON blob out of SharedPreferences, no Activity dependency).
        SharedPreferences preferences = dialogContext.getSharedPreferences(GameMenu.PREF_NAME, Context.MODE_PRIVATE);
        String value = preferences.getString(GameMenu.KEY_NAME, "");
        if (!TextUtils.isEmpty(value)) {
            try {
                KeyConfigHelper.ShortcutFile shortcutFile = KeyConfigHelper.parseShortcutFile(value);
                if (shortcutFile != null && shortcutFile.data != null) {
                    for (KeyConfigHelper.Shortcut sc : shortcutFile.data) {
                        List<String> keys = sc.keys;
                        short[] keyCodes = new short[keys.size()];
                        for (int i = 0; i < keys.size(); i++) {
                            String code = keys.get(i);
                            int keycode;
                            if (code.startsWith("0x")) {
                                keycode = Integer.parseInt(code.substring(2), 16);
                            } else if (code.startsWith("VK_")) {
                                Field field = KeyMapper.class.getDeclaredField(code);
                                keycode = field.getInt(null);
                            } else {
                                throw new IllegalArgumentException("Unknown key code: " + code);
                            }
                            keyCodes[i] = (short) keycode;
                        }
                        options.add(new MenuOption(sc.name, () -> sendKeys(keyCodes)));
                    }
                }
            } catch (Exception e) {
                Toast.makeText(dialogContext, s(R.string.wrong_import_format), Toast.LENGTH_SHORT).show();
            }
        }

        options.add(new MenuOption(s(R.string.game_menu_cancel), null));
        showMenuDialog(s(R.string.game_menu_send_keys), options.toArray(new MenuOption[0]));
    }

    // Full mouse_mode_names list (Multi-touch/Absolute/Trackpad Natural/Trackpad
    // Gaming/Disabled/Absolute-swapped) -- GameInputController.applyMouseMode(int)
    // already handles all of these by index, same as the real app's selectMouseMode().
    // Unlike Game's version, no external-display filtering or local-cursor toggle:
    // the daemon has no concept of "external display" and no inputCaptureProvider
    // local-cursor UI wired up.
    private void showMouseModeMenu() {
        String[] modes = dialogContext.getResources().getStringArray(R.array.mouse_mode_names);
        List<MenuOption> options = new ArrayList<>();
        for (int i = 0; i < modes.length; i++) {
            final int index = i;
            options.add(new MenuOption(modes[i], () -> host.onApplyMouseMode(index)));
        }
        showMenuDialog(s(R.string.game_menu_select_mouse_mode), options.toArray(new MenuOption[0]));
    }

    private void showAdvancedMenu() {
        List<MenuOption> options = new ArrayList<>();
        options.add(new MenuOption(s(R.string.game_menu_toggle_keyboard_model), host::onToggleKeyboard));
        options.add(new MenuOption(s(R.string.game_menu_select_mouse_mode), this::showMouseModeMenu));
        options.add(new MenuOption(s(R.string.game_menu_toggle_hud) + (host.isHudEnabled() ? " (ON)" : " (OFF)"),
                host::onToggleHud));
        options.add(new MenuOption(s(R.string.game_menu_task_manager),
                () -> sendKeys(new short[]{KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_ESCAPE})));
        options.add(new MenuOption(s(R.string.game_menu_send_keys), this::showSpecialKeysMenu));
        options.add(new MenuOption(s(R.string.game_menu_cancel), null));
        showMenuDialog(s(R.string.game_menu_advanced), options.toArray(new MenuOption[0]));
    }

    public void showMenu() {
        List<MenuOption> options = new ArrayList<>();
        options.add(new MenuOption(s(R.string.game_menu_disconnect), host::onDisconnect));
        options.add(new MenuOption(s(R.string.game_menu_toggle_keyboard), host::onToggleKeyboard));
        options.add(new MenuOption(s(R.string.game_menu_rotate_screen), host::onToggleLandscape));
        options.add(new MenuOption(s(R.string.game_menu_advanced), this::showAdvancedMenu));
        options.add(new MenuOption(s(R.string.game_menu_cancel), null));
        showMenuDialog(s(R.string.quick_menu_title), options.toArray(new MenuOption[0]));
    }

    private void sendKeys(short[] keys) { host.onSendKeys(keys); }

    public void hideMenu() {
        if (currentDialog != null && currentDialog.isShowing()) currentDialog.dismiss();
        currentDialog = null;
    }

    public boolean isMenuOpen() {
        return currentDialog != null && currentDialog.isShowing();
    }
}
