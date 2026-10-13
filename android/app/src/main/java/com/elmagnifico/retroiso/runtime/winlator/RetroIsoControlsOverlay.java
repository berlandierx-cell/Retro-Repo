package com.elmagnifico.retroiso.runtime.winlator;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import com.winlator.widget.TouchpadView;
import com.winlator.widget.XServerView;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.XKeycode;
import com.winlator.xserver.XServer;

/**
 * Opt-in Retro ISO controls for legacy 4:3 games.
 *
 * The surface handles absolute touch/clicks in the same letterboxed viewport
 * that Winlator renders. The existing Winlator touchpad is untouched and can
 * be restored at any time using the DIRECT/SOURIS toggle.
 *
 * No game binaries, Wine settings or save files are modified here.
 */
public final class RetroIsoControlsOverlay {
    private static final String PREFERENCES = "retroiso_touch_controls";
    private static final String DIRECT_MODE_KEY = "gangsters2_direct_mode";
    private static final String SELECTION_DRAG_KEY = "gangsters2_selection_drag";

    private final Activity activity;
    private final XServer server;
    private final XServerView gameView;
    private final TouchpadView touchpadView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final View directSurface;
    private final TextView modeButton;
    private final TextView dragModeButton;
    private final TextView shiftButton;
    private final int touchSlop;

    private boolean directMode;
    private boolean tracking;
    private boolean dragging;
    private boolean longPressed;
    private boolean selectionDragMode;
    // Shift is a modifier, not a key pulse. It can remain pressed while
    // the user taps the game or other controls. Never persist across launches.
    private boolean shiftLocked;
    private boolean shiftTouchActive;
    private long shiftTouchStart;
    private Pointer.Button heldDragButton;
    private float downX;
    private float downY;
    private int startMouseX;
    private int startMouseY;
    private Runnable rightClickLongPress;

    public RetroIsoControlsOverlay(Activity activity, FrameLayout root,
                                   XServer server, XServerView gameView,
                                   TouchpadView touchpadView) {
        this.activity = activity;
        this.server = server;
        this.gameView = gameView;
        this.touchpadView = touchpadView;
        touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();

        directSurface = new View(activity);
        directSurface.setClickable(true);
        directSurface.setContentDescription("Zone de clic direct sur le jeu");
        directSurface.setOnTouchListener((view, event) -> onDirectTouch(event));
        root.addView(directSurface, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout arrows = createPanel(168);
        // Gangsters 2 scrolls the city with NUMPAD keys, NOT arrow keys.
        // See original game manual: KP_8 / KP_4 / KP_6 / KP_2.
        arrows.addView(arrowRow(null, XKeycode.KEY_KP_8, null));
        arrows.addView(arrowRow(XKeycode.KEY_KP_4, null, XKeycode.KEY_KP_6));
        arrows.addView(arrowRow(null, XKeycode.KEY_KP_2, null));
        FrameLayout.LayoutParams leftParams = new FrameLayout.LayoutParams(dp(168),
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL);
        leftParams.leftMargin = dp(6);
        root.addView(arrows, leftParams);

        LinearLayout actions = createPanel(112);
        modeButton = actionButton("DIRECT", actions);
        modeButton.setContentDescription("Basculer entre toucher direct et souris classique");
        modeButton.setOnClickListener(v -> setDirectMode(!directMode));

        // Default gesture = right-button drag (pan city). A separate toggle
        // retains left-button drag for bounding-box character selection.
        dragModeButton = actionButton("Glisser: carte", actions);
        dragModeButton.setContentDescription("Choisir glissement de carte ou sélection de zone");
        dragModeButton.setOnClickListener(v -> setSelectionDragMode(!selectionDragMode));

        addAction(actions, "Clic droit", () -> pulsePointer(Pointer.Button.BUTTON_RIGHT));
        addAction(actions, "Entrée", () -> pulseKey(XKeycode.KEY_ENTER));
        addAction(actions, "Échap", () -> pulseKey(XKeycode.KEY_ESC));
        shiftButton = actionButton("MAJ", actions);
        shiftButton.setContentDescription("MAJ : appui court pour verrouiller, appui long pour maintenir temporairement");
        shiftButton.setOnTouchListener((v, event) -> onShiftTouch(event));
        updateShiftAppearance();
        addAction(actions, "Clic gauche", () -> pulsePointer(Pointer.Button.BUTTON_LEFT));
        addAction(actions, "Double clic", () -> {
            pulsePointer(Pointer.Button.BUTTON_LEFT);
            handler.postDelayed(() -> pulsePointer(Pointer.Button.BUTTON_LEFT), 175);
        });
        FrameLayout.LayoutParams rightParams = new FrameLayout.LayoutParams(dp(112),
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL);
        rightParams.rightMargin = dp(6);
        root.addView(actions, rightParams);

        setSelectionDragMode(activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(SELECTION_DRAG_KEY, false));
        setDirectMode(activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(DIRECT_MODE_KEY, true));
    }

    private int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private GradientDrawable background(int fill, int outline) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(10));
        drawable.setStroke(dp(1), outline);
        return drawable;
    }

    private LinearLayout createPanel(int widthDp) {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(5), dp(5), dp(5), dp(5));
        panel.setBackground(background(0x9C101820, 0x8896A8B3));
        return panel;
    }

    private TextView button(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(12);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setBackground(background(0xDA303B4B, 0xFFAABACA));
        view.setClickable(true);
        view.setFocusable(true);
        view.setElevation(dp(2));
        return view;
    }

    private void addArrow(LinearLayout row, XKeycode key) {
        if (key == null) {
            Space space = new Space(activity);
            row.addView(space, new LinearLayout.LayoutParams(dp(50), dp(50)));
            return;
        }
        String glyph;
        switch (key) {
            case KEY_KP_8: glyph = "↑"; break;
            case KEY_KP_2: glyph = "↓"; break;
            case KEY_KP_4: glyph = "←"; break;
            default: glyph = "→"; break;
        }
        TextView view = button(glyph);
        view.setTextSize(24);
        view.setContentDescription("Déplacer la carte vers " + (key == XKeycode.KEY_KP_8 ? "le haut" :
            key == XKeycode.KEY_KP_2 ? "le bas" :
            key == XKeycode.KEY_KP_4 ? "la gauche" : "la droite"));
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    server.injectKeyPress(key);
                    v.setPressed(true);
                    return true;
                case MotionEvent.ACTION_UP:
                    server.injectKeyRelease(key);
                    v.setPressed(false);
                    v.performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    server.injectKeyRelease(key);
                    v.setPressed(false);
                    return true;
                default:
                    return true;
            }
        });
        row.addView(view, new LinearLayout.LayoutParams(dp(50), dp(50)));
    }

    private LinearLayout arrowRow(XKeycode left, XKeycode middle, XKeycode right) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER);
        addArrow(row, left);
        addArrow(row, middle);
        addArrow(row, right);
        return row;
    }

    private TextView actionButton(String title, LinearLayout panel) {
        TextView view = button(title);
        // Keep all action buttons visible in landscape after adding MAJ.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
        lp.bottomMargin = dp(3);
        panel.addView(view, lp);
        return view;
    }

    private void addAction(LinearLayout panel, String title, Runnable action) {
        actionButton(title, panel).setOnClickListener(v -> action.run());
    }

    private void pulsePointer(Pointer.Button button) {
        server.injectPointerButtonPress(button);
        handler.postDelayed(() -> server.injectPointerButtonRelease(button), 85);
    }

    private void pulseKey(XKeycode key) {
        server.injectKeyPress(key);
        handler.postDelayed(() -> server.injectKeyRelease(key), 85);
    }

    /**
     * Short tap: switch Shift lock on/off.
     * Long press: press Shift while the finger stays on the key, then
     * release on finger-up (unless Shift was already locked).
     *
     * Injecting on ACTION_DOWN permits multi-touch Shift + game action.
     * No delayed key pulse: X11/Wine sees a genuine held modifier.
     */
    private boolean onShiftTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                shiftTouchActive = true;
                shiftTouchStart = event.getEventTime();
                if (!shiftLocked) server.injectKeyPress(XKeycode.KEY_SHIFT_L);
                updateShiftAppearance();
                return true;
            case MotionEvent.ACTION_UP:
                if (!shiftTouchActive) return true;
                shiftTouchActive = false;
                boolean shortTap = event.getEventTime() - shiftTouchStart <
                    ViewConfiguration.getLongPressTimeout();
                if (shortTap) shiftLocked = !shiftLocked;
                if (!shiftLocked) server.injectKeyRelease(XKeycode.KEY_SHIFT_L);
                updateShiftAppearance();
                return true;
            case MotionEvent.ACTION_CANCEL:
                shiftTouchActive = false;
                if (!shiftLocked) server.injectKeyRelease(XKeycode.KEY_SHIFT_L);
                updateShiftAppearance();
                return true;
            default:
                return true;
        }
    }

    private void updateShiftAppearance() {
        if (shiftButton == null) return;
        shiftButton.setText(shiftLocked ? "MAJ ✓" : shiftTouchActive ? "MAJ ↓" : "MAJ");
        shiftButton.setBackground(background(
            shiftLocked ? 0xE52B6653 : shiftTouchActive ? 0xDA79602E : 0xDA303B4B,
            shiftLocked ? 0xFF81E1BC : shiftTouchActive ? 0xFFFFD69A : 0xFFAABACA));
        shiftButton.setContentDescription(
            shiftLocked ? "MAJ verrouillée : toucher pour désactiver"
            : shiftTouchActive ? "MAJ maintenue" : "MAJ désactivée : toucher pour verrouiller");
    }

    /** Called by XServerDisplayActivity before stopping Wine. */
    public void releaseHeldKeys() {
        cancelGesture();
        if (shiftTouchActive || shiftLocked)
            server.injectKeyRelease(XKeycode.KEY_SHIFT_L);
        shiftTouchActive = false;
        shiftLocked = false;
        updateShiftAppearance();
    }

    private void setDirectMode(boolean enabled) {
        cancelGesture();
        directMode = enabled;
        directSurface.setVisibility(enabled ? View.VISIBLE : View.GONE);
        modeButton.setText(enabled ? "✓ DIRECT" : "SOURIS");
        modeButton.setBackground(background(enabled ? 0xE52B6653 : 0xDA303B4B,
            enabled ? 0xFF81E1BC : 0xFFAABACA));
        activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(DIRECT_MODE_KEY, enabled).apply();
        // In mouse mode the original Winlator TouchpadView receives events.
        // Do not change the engine's relativeMouseMovement setting or sensitivity.
        touchpadView.setEnabled(true);
    }

    private void setSelectionDragMode(boolean enabled) {
        cancelGesture();
        selectionDragMode = enabled;
        dragModeButton.setText(enabled ? "Glisser: sélection" : "Glisser: carte");
        dragModeButton.setBackground(background(enabled ? 0xD75E4B28 : 0xDA303B4B,
            enabled ? 0xFFFFD69A : 0xFFAABACA));
        activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(SELECTION_DRAG_KEY, enabled).apply();
    }

    private void cancelGesture() {
        if (rightClickLongPress != null) {
            handler.removeCallbacks(rightClickLongPress);
            rightClickLongPress = null;
        }
        if (heldDragButton != null) server.injectPointerButtonRelease(heldDragButton);
        heldDragButton = null;
        dragging = false;
        tracking = false;
        longPressed = false;
    }

    private int[] pointerPosition(float x, float y, boolean clampToGame) {
        int width = gameView.getWidth();
        int height = gameView.getHeight();
        if (width <= 0 || height <= 0) return null;
        int gameWidth = server.screenInfo.width;
        int gameHeight = server.screenInfo.height;
        float originX = 0;
        float originY = 0;
        float renderedW = width;
        float renderedH = height;
        if (!gameView.getRenderer().isFullscreen()) {
            float scale = Math.min((float)width / gameWidth, (float)height / gameHeight);
            renderedW = gameWidth * scale;
            renderedH = gameHeight * scale;
            originX = (width - renderedW) * 0.5f;
            originY = (height - renderedH) * 0.5f;
        }
        if (!clampToGame && (x < originX || x >= originX + renderedW ||
                             y < originY || y >= originY + renderedH)) {
            return null;
        }
        int px = Math.max(0, Math.min(gameWidth - 1,
            (int)((x - originX) * gameWidth / renderedW)));
        int py = Math.max(0, Math.min(gameHeight - 1,
            (int)((y - originY) * gameHeight / renderedH)));
        return new int[]{px, py};
    }

    private boolean onDirectTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                cancelGesture();
                int[] point = pointerPosition(event.getX(), event.getY(), false);
                if (point == null) return true; // Never click on letterbox bands.
                tracking = true;
                downX = event.getX();
                downY = event.getY();
                startMouseX = point[0];
                startMouseY = point[1];
                rightClickLongPress = () -> {
                    if (tracking && !dragging && heldDragButton == null) {
                        longPressed = true;
                        server.injectPointerMove(startMouseX, startMouseY);
                        // Keep RMB held until finger release. Allows long-press
                        // and drag to pan without a synthetic early release.
                        heldDragButton = Pointer.Button.BUTTON_RIGHT;
                        server.injectPointerButtonPress(heldDragButton);
                    }
                };
                handler.postDelayed(rightClickLongPress,
                    ViewConfiguration.getLongPressTimeout());
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!tracking || event.getPointerCount() != 1) return true;
                // Never clamp an active drag into the edge of the game.
                // Leaving the 4:3 viewport must not trigger runaway scrolling.
                int[] point = pointerPosition(event.getX(), event.getY(), false);
                if (point == null) return true;
                if (!longPressed && !dragging &&
                    Math.hypot(event.getX() - downX, event.getY() - downY) > touchSlop) {
                    if (rightClickLongPress != null) handler.removeCallbacks(rightClickLongPress);
                    server.injectPointerMove(startMouseX, startMouseY);
                    // Gangsters 2 pans the city with RIGHT mouse held.
                    // LEFT mouse drag selects several characters instead.
                    heldDragButton = selectionDragMode
                        ? Pointer.Button.BUTTON_LEFT : Pointer.Button.BUTTON_RIGHT;
                    server.injectPointerButtonPress(heldDragButton);
                    dragging = true;
                }
                if (heldDragButton != null) server.injectPointerMove(point[0], point[1]);
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (!tracking) return true;
                boolean wasLongPress = longPressed;
                boolean wasDragging = heldDragButton != null;
                int[] point = pointerPosition(event.getX(), event.getY(), false);
                if (wasDragging && point != null)
                    server.injectPointerMove(point[0], point[1]);
                cancelGesture();
                if (!wasLongPress && !wasDragging && point != null) {
                    server.injectPointerMove(point[0], point[1]);
                    pulsePointer(Pointer.Button.BUTTON_LEFT);
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                cancelGesture();
                return true;
            default:
                return true;
        }
    }
}
