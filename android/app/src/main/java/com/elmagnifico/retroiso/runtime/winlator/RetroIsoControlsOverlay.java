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

    private final Activity activity;
    private final XServer server;
    private final XServerView gameView;
    private final TouchpadView touchpadView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final View directSurface;
    private final TextView modeButton;
    private final int touchSlop;

    private boolean directMode;
    private boolean tracking;
    private boolean dragging;
    private boolean longPressed;
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
        arrows.addView(arrowRow(null, XKeycode.KEY_UP, null));
        arrows.addView(arrowRow(XKeycode.KEY_LEFT, null, XKeycode.KEY_RIGHT));
        arrows.addView(arrowRow(null, XKeycode.KEY_DOWN, null));
        FrameLayout.LayoutParams leftParams = new FrameLayout.LayoutParams(dp(168),
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL);
        leftParams.leftMargin = dp(6);
        root.addView(arrows, leftParams);

        LinearLayout actions = createPanel(112);
        modeButton = actionButton("DIRECT", actions);
        modeButton.setContentDescription("Basculer entre toucher direct et souris classique");
        modeButton.setOnClickListener(v -> setDirectMode(!directMode));

        addAction(actions, "Clic droit", () -> pulsePointer(Pointer.Button.BUTTON_RIGHT));
        addAction(actions, "Entrée", () -> pulseKey(XKeycode.KEY_ENTER));
        addAction(actions, "Échap", () -> pulseKey(XKeycode.KEY_ESC));
        addAction(actions, "Clic gauche", () -> pulsePointer(Pointer.Button.BUTTON_LEFT));
        addAction(actions, "Double clic", () -> {
            pulsePointer(Pointer.Button.BUTTON_LEFT);
            handler.postDelayed(() -> pulsePointer(Pointer.Button.BUTTON_LEFT), 175);
        });
        FrameLayout.LayoutParams rightParams = new FrameLayout.LayoutParams(dp(112),
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL);
        rightParams.rightMargin = dp(6);
        root.addView(actions, rightParams);

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
            case KEY_UP: glyph = "↑"; break;
            case KEY_DOWN: glyph = "↓"; break;
            case KEY_LEFT: glyph = "←"; break;
            default: glyph = "→"; break;
        }
        TextView view = button(glyph);
        view.setTextSize(24);
        view.setContentDescription("Flèche " + (key == XKeycode.KEY_UP ? "haut" :
            key == XKeycode.KEY_DOWN ? "bas" :
            key == XKeycode.KEY_LEFT ? "gauche" : "droite"));
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        lp.bottomMargin = dp(5);
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

    private void cancelGesture() {
        if (rightClickLongPress != null) {
            handler.removeCallbacks(rightClickLongPress);
            rightClickLongPress = null;
        }
        if (dragging) server.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
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
                    if (tracking && !dragging) {
                        longPressed = true;
                        server.injectPointerMove(startMouseX, startMouseY);
                        pulsePointer(Pointer.Button.BUTTON_RIGHT);
                    }
                };
                handler.postDelayed(rightClickLongPress,
                    ViewConfiguration.getLongPressTimeout());
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!tracking || event.getPointerCount() != 1) return true;
                int[] point = pointerPosition(event.getX(), event.getY(), true);
                if (point == null) return true;
                if (!longPressed && !dragging &&
                    Math.hypot(event.getX() - downX, event.getY() - downY) > touchSlop) {
                    if (rightClickLongPress != null) handler.removeCallbacks(rightClickLongPress);
                    server.injectPointerMove(startMouseX, startMouseY);
                    server.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
                    dragging = true;
                }
                if (dragging) server.injectPointerMove(point[0], point[1]);
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (!tracking) return true;
                if (dragging) {
                    int[] point = pointerPosition(event.getX(), event.getY(), true);
                    if (point != null) server.injectPointerMove(point[0], point[1]);
                    cancelGesture();
                }
                else {
                    boolean wasLongPress = longPressed;
                    int[] point = pointerPosition(event.getX(), event.getY(), false);
                    cancelGesture();
                    if (!wasLongPress && point != null) {
                        server.injectPointerMove(point[0], point[1]);
                        pulsePointer(Pointer.Button.BUTTON_LEFT);
                    }
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
