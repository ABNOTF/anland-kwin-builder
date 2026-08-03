package com.anland.consumer;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a FoldCraftLauncher controller (FCL-Controllers JSON) as a floating
 * overlay and turns its buttons / direction pads into Linux input events.
 *
 * The event model follows FCL's ControlButton / ControlDirection behaviour:
 * press / long-press / click / double-click events, auto-keep latching,
 * auto-click repeat, movable buttons, pointer-follow buttons, view-group
 * toggling and the Input (IME) button. The controller *editor* is not ported.
 */
public class FclControllerView extends FrameLayout {

    private static final String TAG = "FclController";

    private static final int LONG_PRESS_MS = 400;
    private static final int AUTO_CLICK_MS = 20;

    // Full FCL keycode table (FCLKeycodes) + anland mouse keys.
    private static final Object[][] KEYCODE_ENTRIES = {
        {0, "RESERVED"},
                {1, "ESC"},
                {2, "1"},
                {3, "2"},
                {4, "3"},
                {5, "4"},
                {6, "5"},
                {7, "6"},
                {8, "7"},
                {9, "8"},
                {10, "9"},
                {11, "0"},
                {12, "MINUS"},
                {13, "EQUAL"},
                {14, "BACKSPACE"},
                {15, "TAB"},
                {16, "Q"},
                {17, "W"},
                {18, "E"},
                {19, "R"},
                {20, "T"},
                {21, "Y"},
                {22, "U"},
                {23, "I"},
                {24, "O"},
                {25, "P"},
                {26, "LEFTBRACE"},
                {27, "RIGHTBRACE"},
                {28, "ENTER"},
                {29, "LEFTCTRL"},
                {30, "A"},
                {31, "S"},
                {32, "D"},
                {33, "F"},
                {34, "G"},
                {35, "H"},
                {36, "J"},
                {37, "K"},
                {38, "L"},
                {39, "SEMICOLON"},
                {40, "APOSTROPHE"},
                {41, "GRAVE"},
                {42, "LEFTSHIFT"},
                {43, "BACKSLASH"},
                {44, "Z"},
                {45, "X"},
                {46, "C"},
                {47, "V"},
                {48, "B"},
                {49, "N"},
                {50, "M"},
                {51, "COMMA"},
                {52, "DOT"},
                {53, "SLASH"},
                {54, "RIGHTSHIFT"},
                {55, "KPASTERISK"},
                {56, "LEFTALT"},
                {57, "SPACE"},
                {58, "CAPSLOCK"},
                {59, "F1"},
                {60, "F2"},
                {61, "F3"},
                {62, "F4"},
                {63, "F5"},
                {64, "F6"},
                {65, "F7"},
                {66, "F8"},
                {67, "F9"},
                {68, "F10"},
                {69, "NUMLOCK"},
                {70, "SCROLLLOCK"},
                {71, "KP7"},
                {72, "KP8"},
                {73, "KP9"},
                {74, "KPMINUS"},
                {75, "KP4"},
                {76, "KP5"},
                {77, "KP6"},
                {78, "KPPLUS"},
                {79, "KP1"},
                {80, "KP2"},
                {81, "KP3"},
                {82, "KP0"},
                {83, "KPDOT"},
                {87, "F11"},
                {88, "F12"},
                {96, "KPENTER"},
                {97, "RIGHTCTRL"},
                {98, "KPSLASH"},
                {99, "SYSRQ"},
                {100, "RIGHTALT"},
                {102, "HOME"},
                {103, "UP"},
                {104, "PAGEUP"},
                {105, "LEFT"},
                {106, "RIGHT"},
                {107, "END"},
                {108, "DOWN"},
                {109, "PAGEDOWN"},
                {110, "INSERT"},
                {111, "DELETE"},
                {117, "KPEQUAL"},
                {119, "PAUSE"},
                {121, "KPCOMMA"},
                {125, "LEFTMATA"},
                {126, "RIGHTMETA"},
                {183, "F13"},
                {184, "F14"},
                {185, "F15"},
                {186, "F16"},
                {187, "F17"},
                {188, "F18"},
                {189, "F19"},
                {190, "F20"},
                {191, "F21"},
                {192, "F22"},
                {193, "F23"},
                {194, "F24"},
                {240, "UNKNOWN"},
        {1000, "鼠标左键"}, {1001, "鼠标中键"}, {1002, "鼠标右键"},
        {1003, "滚轮上"}, {1004, "滚轮下"},
    };

    // FCL special keycodes (see FCLInput.MOUSE_* / FCLKeycodes).
    private static final int FCL_MOUSE_LEFT = 1000;
    private static final int FCL_MOUSE_MIDDLE = 1001;
    private static final int FCL_MOUSE_RIGHT = 1002;
    private static final int FCL_MOUSE_SCROLL_UP = 1003;
    private static final int FCL_MOUSE_SCROLL_DOWN = 1004;

    // Linux input-event-codes.h BTN_* values, matching MainActivity's mouse map.
    private static final int EV_BTN_LEFT = 0x110;
    private static final int EV_BTN_RIGHT = 0x111;
    private static final int EV_BTN_MIDDLE = 0x112;

    /** Bridge from controller events to the anland native input pipeline. */
    public interface Bridge {
        void key(int action, int evdev);                    // 0 = down, 1 = up
        void mouseButton(int button, boolean pressed);
        void mouseMove(float dx, float dy);
        void mouseScroll(int axis, float value, int discrete);
        void text(String text);
        void toggleIme();
        void toggleVirtualKeyboard();
        void openSettings();
        /** Switch the current orientation's controller profile; null = default. */
        void selectController(String id);
        /** The overlay must yield while its editor dialogs are open: the overlay
         *  window sits above any app dialog and would swallow its touches. */
        void setEditorDialogOpen(boolean open);
    }

    /**
     * Receives touches that land outside the controller controls. The overlay
     * routes every pointer itself while visible so that holding a button and
     * swiping the desktop surface work at the same time (multi-touch).
     */
    public interface SurfaceTouchForwarder {
        boolean onSurfaceTouch(MotionEvent event);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float density;
    private final List<View> controls = new ArrayList<>();
    private final Map<String, Boolean> groupVisible = new HashMap<>();
    private FclController controller;
    private Bridge bridge;
    private float mouseSensitivity = 1f;
    private boolean editMode = false;
    private SurfaceTouchForwarder surfaceForwarder;
    private final List<View> passThroughViews = new ArrayList<>();
    private final Map<View, Integer> controlPointers = new HashMap<>();
    // The single surface (desktop/touchpad) pointer, if one is being tracked.
    // A pointer keeps the role it was assigned on DOWN for its whole lifetime:
    // a control pointer is never also forwarded to the surface, and vice versa,
    // so a finger that slides off a button cannot fight the touchpad finger.
    private int surfacePointerId = -1;

    // Position overrides: control id -> [x thousandths, y thousandths].
    // Saved overrides survive rebuilds; pending ones only exist while editing.
    private final Map<String, int[]> savedPositions = new HashMap<>();
    private final Map<String, int[]> pendingPositions = new HashMap<>();
    private static final String PREFS_NAME = "anland_settings";

    public FclControllerView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setClipChildren(false);
    }

    public void setBridge(Bridge bridge) {
        this.bridge = bridge;
    }

    /** Show a dialog from the overlay context, hiding the overlay first so the
     *  fullscreen overlay window cannot block the dialog's touches. */
    private void showOverlayDialog(android.app.Dialog dialog) {
        if (bridge != null) {
            bridge.setEditorDialogOpen(true);
        }
        dialog.setOnDismissListener(d -> {
            if (bridge != null) {
                bridge.setEditorDialogOpen(false);
            }
        });
        dialog.show();
    }

    public void setSurfaceTouchForwarder(SurfaceTouchForwarder forwarder) {
        this.surfaceForwarder = forwarder;
    }

    /** Other overlays (IME, extra-keys bar...) that keep normal touch dispatch. */
    public void setPassThroughViews(List<View> views) {
        passThroughViews.clear();
        if (views != null) {
            passThroughViews.addAll(views);
        }
    }

    public void setController(FclController controller) {
        this.controller = controller;
        rebuild();
    }

    public boolean hasController() {
        return controller != null;
    }

    /** Toggle layout-editing mode: controls can be dragged and repositioned. */
    public void setEditMode(boolean editMode) {
        this.editMode = editMode;
        if (!editMode) {
            pendingPositions.clear();
        }
        invalidate();
    }

    public boolean isEditMode() {
        return editMode;
    }

    /** Persist pending drag positions as the saved layout for this controller. */
    public void savePositions() {
        if (controller == null) {
            return;
        }
        savedPositions.putAll(pendingPositions);
        pendingPositions.clear();
        writePositions();
        setEditMode(false);
        rebuild();
    }

    /** Discard pending drag positions and go back to the saved layout. */
    public void discardPositions() {
        pendingPositions.clear();
        setEditMode(false);
        rebuild();
    }

    /** Clear all saved position overrides and restore the original controller JSON layout. */
    public void resetPositions() {
        savedPositions.clear();
        pendingPositions.clear();
        writePositions();
        setEditMode(false);
        rebuild();
    }

    public void setMouseSensitivity(float sensitivity) {
        this.mouseSensitivity = sensitivity;
    }

    public void toggleGroup(String groupId) {
        Boolean visible = groupVisible.get(groupId);
        if (visible == null) {
            return;
        }
        boolean next = !visible;
        groupVisible.put(groupId, next);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (groupId.equals(child.getTag())) {
                child.setVisibility(next ? VISIBLE : GONE);
            }
        }
    }

    /** Rebuild all controls for the current controller and overlay size. */
    public void rebuild() {
        if (controller == null) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            // A GONE view is never measured (width/height stay 0); rebuilding is
            // triggered again from setVisibility(VISIBLE) once it can be laid out.
            if (getVisibility() != GONE) {
                post(this::rebuild);
            }
            return;
        }

        removeAllViews();
        controls.clear();
        groupVisible.clear();
        loadPositions();

        for (FclController.ViewGroup group : controller.viewGroups) {
            boolean visible = "VISIBLE".equals(group.visibility);
            groupVisible.put(group.id, visible);
            for (FclController.Button button : group.buttons) {
                if ("EDIT".equals(button.baseInfo.visibilityType)) {
                    continue;
                }
                FclButtonView view = new FclButtonView(getContext(), button);
                view.setTag(group.id);
                view.setVisibility(visible ? VISIBLE : GONE);
                addView(view);
                controls.add(view);
            }
        for (FclController.Direction direction : group.directions) {
                if ("EDIT".equals(direction.baseInfo.visibilityType)) {
                    continue;
                }
                FclDirectionView view = new FclDirectionView(getContext(), direction);
                view.setTag(group.id);
                view.setVisibility(visible ? VISIBLE : GONE);
                addView(view);
                controls.add(view);
            }
        }
        requestLayout();
        postInvalidate();
    }

    /** Save pending drag positions and per-key edits into the controller file. */
    public void saveEdit() {
        applyPendingPositionsToJson();
        controller.saveToFile(getContext());
        reloadController();
        setEditMode(false);
    }

    private void applyPendingPositionsToJson() {
        if (controller == null) {
            return;
        }
        for (Map.Entry<String, int[]> e : pendingPositions.entrySet()) {
            JSONObject ctrl = controller.findControlJson(e.getKey());
            if (ctrl == null) {
                continue;
            }
            int[] pos = e.getValue();
            try {
                JSONObject base = ctrl.optJSONObject("baseInfo");
                if (base == null) {
                    base = new JSONObject();
                    ctrl.put("baseInfo", base);
                }
                base.put("xPosition", pos[0]);
                base.put("yPosition", pos[1]);
            } catch (JSONException ignored) {
            }
        }
    }

    /** Back / 完成 in edit mode: ask whether to keep the changes. */
    public void promptExitEditMode() {
        if (!editMode) {
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setTitle("保存修改？")
                .setMessage("是否保存对控制器的修改？")
                .setPositiveButton("保存", (d, w) -> saveEdit())
                .setNegativeButton("不保存", (d, w) -> discardPositions())
                .setNeutralButton("取消", null)
                .create();
        showOverlayDialog(dialog);
    }

    /** Re-parse the controller from disk/asset (after edits) and rebuild. */
    private void reloadController() {
        if (controller == null) {
            return;
        }
        FclController fresh = FclController.load(getContext(), controller.id);
        if (fresh != null) {
            controller = fresh;
            savedPositions.clear();
            pendingPositions.clear();
            writePositions();
            rebuild();
        }
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility == VISIBLE && controller != null) {
            rebuild();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0 && controller != null && getVisibility() == VISIBLE) {
            rebuild();
        }
    }

    /**
     * Multi-touch routing. While the overlay is visible every pointer is handled
     * here: pointers inside a control go to that control (so several buttons can
     * be pressed at once), pointers outside go to the surface forwarder. Without
     * this, Android gives the whole gesture to whichever view got the first
     * touch, making it impossible to hold a key and swipe the screen together.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (controller == null || getVisibility() != VISIBLE || editMode
                || surfaceForwarder == null || hitPassThrough(ev)) {
            return super.dispatchTouchEvent(ev);
        }
        routeTouchEvent(ev);
        return true;
    }

    private boolean hitPassThrough(MotionEvent ev) {
        if (passThroughViews.isEmpty()) {
            return false;
        }
        for (int i = 0; i < ev.getPointerCount(); i++) {
            float x = ev.getX(i);
            float y = ev.getY(i);
            for (View v : passThroughViews) {
                if (v != null && v.getVisibility() == VISIBLE
                        && x >= v.getX() && x <= v.getX() + v.getWidth()
                        && y >= v.getY() && y <= v.getY() + v.getHeight()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void routeTouchEvent(MotionEvent ev) {
        int masked = ev.getActionMasked();
        int idx = ev.getActionIndex();
        View control = controlAt(ev.getX(idx), ev.getY(idx));
        switch (masked) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (control != null) {
                    controlPointers.put(control, ev.getPointerId(idx));
                    dispatchControl(control, ev, MotionEvent.ACTION_DOWN, idx);
                } else {
                    surfacePointerDown(ev, idx);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                List<Map.Entry<View, Integer>> entries =
                        new ArrayList<>(controlPointers.entrySet());
                for (Map.Entry<View, Integer> e : entries) {
                    int pi = pointerIndex(ev, e.getValue());
                    if (pi >= 0) {
                        dispatchControl(e.getKey(), ev, MotionEvent.ACTION_MOVE, pi);
                    }
                }
                int si = pointerIndex(ev, surfacePointerId);
                if (si >= 0) {
                    forwardSurface(ev, MotionEvent.ACTION_MOVE, si);
                }
                break;

            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
                int pid = ev.getPointerId(idx);
                View mapped = controlByPointerId(pid);
                if (mapped != null) {
                    dispatchControl(mapped, ev, MotionEvent.ACTION_UP, idx);
                    controlPointers.remove(mapped);
                } else if (ev.getPointerId(idx) == surfacePointerId) {
                    forwardSurface(ev, MotionEvent.ACTION_UP, idx);
                    surfacePointerId = -1;
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                for (Map.Entry<View, Integer> e : new ArrayList<>(controlPointers.entrySet())) {
                    int pi = pointerIndex(ev, e.getValue());
                    dispatchControl(e.getKey(), ev, MotionEvent.ACTION_CANCEL, Math.max(0, pi));
                }
                controlPointers.clear();
                if (surfacePointerId >= 0) {
                    forwardSurface(ev, MotionEvent.ACTION_CANCEL, idx);
                    surfacePointerId = -1;
                }
                break;
        }
    }

    private void surfacePointerDown(MotionEvent ev, int idx) {
        if (surfacePointerId >= 0) {
            return; // a second surface finger is not tracked while controls are active
        }
        surfacePointerId = ev.getPointerId(idx);
        forwardSurface(ev, MotionEvent.ACTION_DOWN, idx);
    }

    private void dispatchControl(View view, MotionEvent src, int action, int idx) {
        float x = src.getX(idx) - view.getX();
        float y = src.getY(idx) - view.getY();
        MotionEvent e = MotionEvent.obtain(src.getDownTime(), src.getEventTime(),
                action, x, y, src.getMetaState());
        view.onTouchEvent(e);
        e.recycle();
    }

    private void forwardSurface(MotionEvent src, int action, int idx) {
        MotionEvent e = MotionEvent.obtain(src.getDownTime(), src.getEventTime(),
                action, src.getX(idx), src.getY(idx), src.getMetaState());
        surfaceForwarder.onSurfaceTouch(e);
        e.recycle();
    }

    private int pointerIndex(MotionEvent ev, int pointerId) {
        for (int i = 0; i < ev.getPointerCount(); i++) {
            if (ev.getPointerId(i) == pointerId) {
                return i;
            }
        }
        return -1;
    }

    private View controlByPointerId(int pointerId) {
        for (Map.Entry<View, Integer> e : controlPointers.entrySet()) {
            if (e.getValue() == pointerId) {
                return e.getKey();
            }
        }
        return null;
    }

    private View controlAt(float x, float y) {
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View child = getChildAt(i);
            if (child.getVisibility() != VISIBLE) {
                continue;
            }
            if (x >= child.getX() && x <= child.getX() + child.getWidth()
                    && y >= child.getY() && y <= child.getY() + child.getHeight()) {
                return child;
            }
        }
        return null;
    }

    /** Release every held key/button (call when hiding the overlay). */
    public void releaseAll() {
        for (View v : controls) {
            if (v instanceof FclButtonView) {
                ((FclButtonView) v).releaseAll();
            } else if (v instanceof FclDirectionView) {
                ((FclDirectionView) v).releaseAll();
            }
        }
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    private GradientDrawable roundedDrawable(int color, int radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    private Button accentButton(String text) {
        Button b = new Button(getContext());
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        b.setBackground(roundedDrawable(0xFF2196F3, dp(10)));
        return b;
    }

    private Button tabButton(String text, boolean selected) {
        Button b = new Button(getContext());
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(selected ? 0xFFFFFFFF : 0xFF2196F3);
        b.setBackground(roundedDrawable(selected ? 0xFF2196F3 : 0x22000000, dp(10)));
        return b;
    }

    /** FCL-style rail icon button (⚙ / ⌨), 44dp, tinted when selected. */
    private Button railIcon(String glyph) {
        Button b = new Button(getContext());
        b.setText(glyph);
        b.setTextSize(18);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        b.setTextColor(0xFF2196F3);
        b.setBackground(roundedDrawable(0x00000000, dp(10)));
        return b;
    }

    private TextView sectionHeader(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTextColor(0xFF757575);
        tv.setPadding(0, dp(6), 0, dp(2));
        return tv;
    }

    private View divider() {
        View v = new View(getContext());
        v.setBackgroundColor(0xFFBDBDBD);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    private TextView formLabel(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(0xFF424242);
        tv.setSingleLine(true);
        return tv;
    }

    /** Label on the left, control on the right (FCL form-row style). */
    private LinearLayout formRow(View label, View control) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));
        row.addView(label);
        View spacer = new View(getContext());
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1f));
        row.addView(spacer);
        row.addView(control);
        return row;
    }

    private EditText fclEditText(String hint) {
        EditText et = new EditText(getContext());
        et.setHint(hint);
        et.setTextSize(14);
        et.setSingleLine(true);
        return et;
    }

    private Switch fclSwitch(String text) {
        Switch s = new Switch(getContext());
        // The row label carries the name; the switch itself stays bare so the
        // tiny ON/OFF track text cannot collide with a second label.
        s.setText("");
        s.setShowText(false);
        s.setTextSize(14);
        return s;
    }

    private Button ghostButton(String text) {
        Button b = new Button(getContext());
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        b.setTextColor(0xFF2196F3);
        b.setBackground(roundedDrawable(0x00000000, dp(8)));
        return b;
    }

    private JSONObject groupJsonForControl(String controlId) {
        if (controller == null) {
            return null;
        }
        for (FclController.ViewGroup g : controller.viewGroups) {
            for (FclController.Button b : g.buttons) {
                if (b.id.equals(controlId)) {
                    return controller.findGroupJson(g.id);
                }
            }
            for (FclController.Direction d : g.directions) {
                if (d.id.equals(controlId)) {
                    return controller.findGroupJson(g.id);
                }
            }
        }
        return null;
    }

        private void openKeycodePicker(final List<Integer> codes, final Runnable after) {
            final int n = KEYCODE_ENTRIES.length;
            String[] labels = new String[n];
            boolean[] checked = new boolean[n];
            for (int i = 0; i < n; i++) {
                int code = (Integer) KEYCODE_ENTRIES[i][0];
                labels[i] = KEYCODE_ENTRIES[i][1] + " (" + code + ")";
                checked[i] = codes.contains(code);
            }
            AlertDialog dialog = new AlertDialog.Builder(getContext())
                    .setTitle("选择键码")
                    .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> {
                        int code = (Integer) KEYCODE_ENTRIES[which][0];
                        if (isChecked) {
                            if (!codes.contains(code)) {
                                codes.add(code);
                            }
                        } else {
                            codes.remove((Integer) code);
                        }
                    })
                    .setPositiveButton("确定", (d, w) -> after.run())
                    .setNegativeButton("取消", null)
                    .create();
            showOverlayDialog(dialog);
        }

        private int parseIntClamped(String s, int min, int max, int def) {
            try {
                int v = Integer.parseInt(s.trim());
                return Math.max(min, Math.min(max, v));
            } catch (Exception e) {
                return def;
            }
        }

        private String joinCodes(List<Integer> codes) {
            StringBuilder sb = new StringBuilder();
            for (int c : codes) {
                if (sb.length() > 0) {
                    sb.append(",");
                }
                sb.append(c);
            }
            return sb.length() == 0 ? "（无）" : sb.toString();
        }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        if (w <= 0 || h <= 0) {
            w = getResources().getDisplayMetrics().widthPixels;
            h = getResources().getDisplayMetrics().heightPixels;
        }
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            int cw = childWidth(child, w, h);
            int ch = childHeight(child, w, h);
            if (cw <= 0 || ch <= 0) {
                // Non-control children (the management toolbar) measure
                // themselves from their own layout params / content.
                child.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            } else {
                child.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
            }
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int w = right - left;
        int h = bottom - top;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            int cw = child.getMeasuredWidth();
            int ch = child.getMeasuredHeight();
            int x = 0;
            int y = 0;
            if (child instanceof FclButtonView) {
                FclController.BaseInfo base = ((FclButtonView) child).data.baseInfo;
                int[] pos = positionFor(((FclButtonView) child).data.id, base, w, h);
                x = pos[0] <= 0 ? 0 : (int) ((w - cw) * (pos[0] / 1000f));
                y = pos[1] <= 0 ? 0 : (int) ((h - ch) * (pos[1] / 1000f));
            } else if (child instanceof FclDirectionView) {
                FclController.BaseInfo base = ((FclDirectionView) child).data.baseInfo;
                int[] pos = positionFor(((FclDirectionView) child).data.id, base, w, h);
                x = pos[0] <= 0 ? 0 : (int) ((w - cw) * (pos[0] / 1000f));
                y = pos[1] <= 0 ? 0 : (int) ((h - ch) * (pos[1] / 1000f));
            }
            child.layout(x, y, x + cw, y + ch);
        }
    }

    private int[] positionFor(String id, FclController.BaseInfo base, int w, int h) {
        int[] p = pendingPositions.get(id);
        if (p == null) {
            p = savedPositions.get(id);
        }
        if (p != null) {
            return p;
        }
        return new int[]{base.xPosition, base.yPosition};
    }

    /** Record a drag result (in thousandths of the free area) while editing. */
    public void setPendingPosition(String id, int xThousandths, int yThousandths) {
        pendingPositions.put(id, new int[]{
                Math.max(0, Math.min(1000, xThousandths)),
                Math.max(0, Math.min(1000, yThousandths))});
    }

    private void loadPositions() {
        savedPositions.clear();
        if (controller == null) {
            return;
        }
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString("fcl_pos_" + controller.id, null);
        if (json == null || json.isEmpty()) {
            return;
        }
        try {
            JSONObject obj = new JSONObject(json);
            JSONArray ids = obj.names();
            if (ids == null) {
                return;
            }
            for (int i = 0; i < ids.length(); i++) {
                String id = ids.getString(i);
                JSONArray arr = obj.optJSONArray(id);
                if (arr != null && arr.length() >= 2) {
                    savedPositions.put(id, new int[]{arr.optInt(0, 0), arr.optInt(1, 0)});
                }
            }
        } catch (JSONException ignored) {
            // Corrupt override data: fall back to the original layout.
        }
    }

    private void writePositions() {
        if (controller == null) {
            return;
        }
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        JSONObject obj = new JSONObject();
        for (Map.Entry<String, int[]> e : savedPositions.entrySet()) {
            try {
                obj.put(e.getKey(), new JSONArray().put(e.getValue()[0]).put(e.getValue()[1]));
            } catch (JSONException ignored) {
            }
        }
        prefs.edit().putString("fcl_pos_" + controller.id, obj.toString()).apply();
    }

    private int childWidth(View child, int w, int h) {
        if (child instanceof FclButtonView) {
            return ((FclButtonView) child).data.baseInfo.widthPx(w, h, density);
        }
        if (child instanceof FclDirectionView) {
            return ((FclDirectionView) child).data.baseInfo.widthPx(w, h, density);
        }
        return 0;
    }

    private int childHeight(View child, int w, int h) {
        if (child instanceof FclButtonView) {
            return ((FclButtonView) child).data.baseInfo.heightPx(w, h, density);
        }
        if (child instanceof FclDirectionView) {
            return ((FclDirectionView) child).data.baseInfo.widthPx(w, h, density);
        }
        return 0;
    }

    // ======================================================================
    // Button
    // ======================================================================

    private final class FclButtonView extends View {
        private static final int EVENT_PRESS = 0;
        private static final int EVENT_LONG_PRESS = 1;
        private static final int EVENT_CLICK = 2;
        private static final int EVENT_DOUBLE_CLICK = 3;

        private final FclController.Button data;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        // Movement dead zone for pointer-follow: a tap's micro-jitter must not
        // move the host cursor (it would drift the camera right before a click).
        private final float touchSlop;

        private boolean pressed = false;
        private boolean moved = false;
        private boolean longPressFired = false;
        // Set once the finger has clearly travelled past touchSlop; from then on
        // every MOVE is emitted, so slow drags stay smooth instead of chunking.
        private boolean pointerFollowActive = false;
        private float downX, downY;
        private long downTime;
        private int clickCount = 0;
        private long firstClickTime;

        private FclController.Event autoClickEvent;
        private boolean autoClickRunning = false;
        // Auto-keep (latch) state per event kind, matching FCL: the first press
        // latches the key and keeps the pressed (red) style; the next press
        // releases it and restores the normal style.
        private final boolean[] keepActive = new boolean[4];
        private final Runnable autoClickRunnable = new Runnable() {
            @Override
            public void run() {
                if (autoClickEvent == null) {
                    return;
                }
                keyDown(autoClickEvent);
                keyUp(autoClickEvent);
                if (autoClickRunning) {
                    handler.postDelayed(this, AUTO_CLICK_MS);
                }
            }
        };

        private final Runnable longPressRunnable = new Runnable() {
            @Override
            public void run() {
                longPressFired = true;
                trigger(data.longPressEvent, true, false, EVENT_LONG_PRESS);
                if (data.longPressEvent != null && data.longPressEvent.autoKeep
                        && !keepActive[EVENT_LONG_PRESS]) {
                    pressed = false;
                }
                invalidate();
            }
        };

        FclButtonView(Context context, FclController.Button data) {
            super(context);
            this.data = data;
            this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            setClickable(true);
            setWillNotDraw(false);
            strokePaint.setStyle(Paint.Style.STROKE);
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            FclController.ButtonStyle s = data.style;
            float stroke = dp((pressed ? s.strokeWidthPressed : s.strokeWidth) / 10f);
            float corner = dp((pressed ? s.cornerRadiusPressed : s.cornerRadius) / 10f);

            rect.set(stroke, stroke, getWidth() - stroke, getHeight() - stroke);
            fillPaint.setColor(pressed ? s.fillColorPressed : s.fillColor);
            strokePaint.setColor(pressed ? s.strokeColorPressed : s.strokeColor);
            strokePaint.setStrokeWidth(stroke);
            canvas.drawRoundRect(rect, corner, corner, fillPaint);
            canvas.drawRoundRect(rect, corner, corner, strokePaint);

            if (editMode) {
                float eb = dp(2);
                rect.set(eb, eb, getWidth() - eb, getHeight() - eb);
                strokePaint.setColor(0xFFFF4444);
                strokePaint.setStrokeWidth(eb);
                canvas.drawRoundRect(rect, corner, corner, strokePaint);
            }

            String text = data.text;
            if (text == null || text.isEmpty()) {
                return;
            }
            textPaint.setColor(pressed ? s.textColorPressed : s.textColor);
            textPaint.setTextSize((pressed ? s.textSizePressed : s.textSize) * density);
            textPaint.setTextAlign(Paint.Align.CENTER);
            String[] lines = text.split("\n", -1);
            float lineHeight = textPaint.getFontSpacing();
            float totalHeight = lineHeight * lines.length;
            float y0 = (getHeight() - totalHeight) / 2f;
            for (int i = 0; i < lines.length; i++) {
                float baseline = y0 + lineHeight * (i + 0.5f)
                        - (textPaint.ascent() + textPaint.descent()) / 2f;
                canvas.drawText(lines[i], getWidth() / 2f, baseline, textPaint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (editMode) {
                return handleEditTouch(event);
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    downTime = System.currentTimeMillis();
                    moved = false;
                    longPressFired = false;
                    pointerFollowActive = false;
                    trigger(data.pressEvent, true, false, EVENT_PRESS);
                    pressed = true;
                    // A second press toggles an auto-keep latch off; do not show
                    // the pressed style for that release tap.
                    if (data.pressEvent != null && data.pressEvent.autoKeep
                            && !keepActive[EVENT_PRESS]) {
                        pressed = false;
                    }
                    invalidate();
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (!pointerFollowActive
                            && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                        // Confirmed drag: drop the pre-slop travel so the first
                        // emitted delta is small, then stream every frame.
                        pointerFollowActive = true;
                        moved = true;
                        handler.removeCallbacks(longPressRunnable);
                        downX = event.getX();
                        downY = event.getY();
                    }
                    if (pointerFollowActive && (data.pointerFollow || data.dragMoveMouse)) {
                        bridge.mouseMove((event.getX() - downX) * mouseSensitivity,
                                (event.getY() - downY) * mouseSensitivity);
                        downX = event.getX();
                        downY = event.getY();
                    }
                    if (data.movable) {
                        float nx = clamp(getX() + dx, 0, getParentWidth() - getWidth());
                        float ny = clamp(getY() + dy, 0, getParentHeight() - getHeight());
                        setX(nx);
                        setY(ny);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(longPressRunnable);
                    if (longPressFired) {
                        releaseEvent(data.longPressEvent, false, EVENT_LONG_PRESS);
                        longPressFired = false;
                    }
                    releaseEvent(data.pressEvent, false, EVENT_PRESS);

                    boolean tap = !moved && System.currentTimeMillis() - downTime <= 100;
                    if (tap) {
                        trigger(data.clickEvent, true, true, EVENT_CLICK);
                        clickCount++;
                        if (clickCount == 1) {
                            firstClickTime = System.currentTimeMillis();
                        } else if (clickCount == 2) {
                            if (System.currentTimeMillis() - firstClickTime < 400) {
                                trigger(data.doubleClickEvent, true, true, EVENT_DOUBLE_CLICK);
                            } else {
                                clickCount = 1;
                                firstClickTime = System.currentTimeMillis();
                            }
                            clickCount = 0;
                        }
                    }
                    pressed = anyKeepActive();
                    invalidate();
                    return true;
            }
            return true;
        }

        private boolean handleEditTouch(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (Math.abs(dx) > 2 || Math.abs(dy) > 2) {
                        moved = true;
                    }
                    float nx = clamp(getX() + dx, 0, getParentWidth() - getWidth());
                    float ny = clamp(getY() + dy, 0, getParentHeight() - getHeight());
                    setX(nx);
                    setY(ny);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (moved) {
                        int pw = getParentWidth();
                        int ph = getParentHeight();
                        int xTh = pw > getWidth()
                                ? Math.round(1000f * getX() / (pw - getWidth())) : 0;
                        int yTh = ph > getHeight()
                                ? Math.round(1000f * getY() / (ph - getHeight())) : 0;
                        setPendingPosition(data.id, xTh, yTh);
                    } else {
                        openPropertyDialog();
                    }
                    return true;
            }
            return true;
        }

        /** FCL-style editor: 信息/事件 tabs, four event types, keycode picker. */
        private void openPropertyDialog() {
            if (controller == null) {
                return;
            }
            final JSONObject btn = controller.findControlJson(data.id);
            if (btn == null) {
                return;
            }
            final String[] eventKeys = {"pressEvent", "longPressEvent",
                    "clickEvent", "doubleClickEvent"};
            final String[] eventTabs = {"按下", "长按", "单击", "双击"};
            final String[] flagNames = {"持续按住", "连点", "打开菜单",
                    "切换触摸模式", "切换鼠标模式", "输入", "快捷输入"};
            final String[] flagKeys = {"autoKeep", "autoClick", "openMenu",
                    "switchTouchMode", "switchMouseMode", "input", "quickInput"};
            final int pad = dp(12);

            LinearLayout root = new LinearLayout(getContext());
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(pad, pad, pad, pad);
            root.setBackground(roundedDrawable(0xFFF5F5F5, dp(16)));

            TextView titleView = new TextView(getContext());
            titleView.setText("编辑按键" + (data.text == null || data.text.isEmpty()
                    ? "" : " · " + data.text));
            titleView.setTextSize(17);
            titleView.setTypeface(null, Typeface.BOLD);
            titleView.setTextColor(0xFF212121);
            titleView.setPadding(0, 0, 0, dp(8));
            root.addView(titleView);
            root.addView(sectionHeader("方向控件设置"));
            root.addView(divider());

            // ---- FCL-style: left icon rail (信息/事件) + scrollable content ----
            LinearLayout body = new LinearLayout(getContext());
            body.setOrientation(LinearLayout.HORIZONTAL);

            LinearLayout rail = new LinearLayout(getContext());
            rail.setOrientation(LinearLayout.VERTICAL);
            rail.setPadding(0, dp(4), dp(8), 0);
            final Button infoTab = railIcon("⚙");
            final Button eventTab = railIcon("⌨");
            rail.addView(infoTab);
            rail.addView(eventTab);
            body.addView(rail);

            final ScrollView scroll = new ScrollView(getContext());
            LinearLayout content = new LinearLayout(getContext());
            content.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(content);
            body.addView(scroll, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            root.addView(body, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

            // ---- info panel ----
            LinearLayout infoPanel = new LinearLayout(getContext());
            infoPanel.setOrientation(LinearLayout.VERTICAL);
            infoPanel.addView(sectionHeader("信息"));
            infoPanel.addView(divider());

            EditText textInput = fclEditText("按键文字");
            textInput.setText(data.text);
            infoPanel.addView(formRow(formLabel("文字"), textInput));

            Switch dragSwitch = fclSwitch("按住拖动移动鼠标");
            dragSwitch.setChecked(data.dragMoveMouse);
            infoPanel.addView(formRow(formLabel("按住拖动移动鼠标"), dragSwitch));

            EditText xInput = fclEditText("0-1000");
            xInput.setInputType(InputType.TYPE_CLASS_NUMBER);
            xInput.setText(String.valueOf(data.baseInfo.xPosition));
            xInput.setWidth(dp(120));
            infoPanel.addView(formRow(formLabel("X位置"), xInput));

            EditText yInput = fclEditText("0-1000");
            yInput.setInputType(InputType.TYPE_CLASS_NUMBER);
            yInput.setText(String.valueOf(data.baseInfo.yPosition));
            yInput.setWidth(dp(120));
            infoPanel.addView(formRow(formLabel("Y位置"), yInput));

            Spinner sizeSpinner = new Spinner(getContext());
            String[] sizeNames = {"百分比", "绝对dp"};
            sizeSpinner.setAdapter(new ArrayAdapter<>(getContext(),
                    android.R.layout.simple_spinner_dropdown_item, sizeNames));
            sizeSpinner.setSelection("ABSOLUTE".equals(data.baseInfo.sizeType) ? 1 : 0);
            infoPanel.addView(formRow(formLabel("尺寸类型"), sizeSpinner));

            Spinner refSpinner = new Spinner(getContext());
            String[] refNames = {"参照屏宽", "参照屏高"};
            refSpinner.setAdapter(new ArrayAdapter<>(getContext(),
                    android.R.layout.simple_spinner_dropdown_item, refNames));
            refSpinner.setSelection("SCREEN_WIDTH"
                    .equals(data.baseInfo.percentageWidth.reference) ? 0 : 1);
            infoPanel.addView(formRow(formLabel("参照"), refSpinner));

            EditText sizeInput = fclEditText("0-1000 或 dp");
            sizeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
            int sizeVal = "ABSOLUTE".equals(data.baseInfo.sizeType)
                    ? data.baseInfo.absoluteWidth
                    : data.baseInfo.percentageWidth.size;
            sizeInput.setText(String.valueOf(sizeVal));
            sizeInput.setWidth(dp(120));
            infoPanel.addView(formRow(formLabel("大小"), sizeInput));

            Spinner styleSpinner = new Spinner(getContext());
            List<String> styleNames = new ArrayList<>(controller.buttonStylesByName.keySet());
            if (styleNames.isEmpty()) {
                styleNames.add("Default");
            }
            styleSpinner.setAdapter(new ArrayAdapter<>(getContext(),
                    android.R.layout.simple_spinner_dropdown_item, styleNames));
            int styleIdx = styleNames.indexOf(btn.optString("style", "Default"));
            styleSpinner.setSelection(styleIdx < 0 ? 0 : styleIdx);
            infoPanel.addView(formRow(formLabel("样式"), styleSpinner));

            // ---- event panel ----
            LinearLayout eventPanel = new LinearLayout(getContext());
            eventPanel.setOrientation(LinearLayout.VERTICAL);
            eventPanel.addView(sectionHeader("事件"));
            eventPanel.addView(divider());

            Switch pointerSwitch = fclSwitch("指针跟随 (pointerFollow)");
            pointerSwitch.setChecked(data.pointerFollow);
            eventPanel.addView(formRow(formLabel("指针跟随"), pointerSwitch));

            Switch movableSwitch = fclSwitch("可移动 (Movable)");
            movableSwitch.setChecked(data.movable);
            eventPanel.addView(formRow(formLabel("可移动"), movableSwitch));

            final List<List<Integer>> evCodes = new ArrayList<>();
            final String[] evText = new String[4];
            final boolean[][] evFlags = new boolean[4][7];
            for (int i = 0; i < 4; i++) {
                JSONObject e = btn.optJSONObject("event") != null
                        ? btn.optJSONObject("event").optJSONObject(eventKeys[i]) : null;
                evText[i] = e != null ? e.optString("outputText", "") : "";
                List<Integer> codes = new ArrayList<>();
                JSONArray arr = e != null ? e.optJSONArray("outputKeycodes") : null;
                if (arr != null) {
                    for (int k = 0; k < arr.length(); k++) {
                        codes.add(arr.optInt(k, 0));
                    }
                }
                evCodes.add(codes);
                for (int f = 0; f < 7; f++) {
                    evFlags[i][f] = e != null && e.optBoolean(flagKeys[f], false);
                }
            }

            LinearLayout eventTabBar = new LinearLayout(getContext());
            eventTabBar.setOrientation(LinearLayout.HORIZONTAL);
            eventTabBar.setPadding(0, dp(4), 0, dp(4));
            final Button[] eventTabButtons = new Button[4];
            for (int i = 0; i < 4; i++) {
                eventTabButtons[i] = tabButton(eventTabs[i], i == 0);
                eventTabBar.addView(eventTabButtons[i]);
            }
            eventPanel.addView(eventTabBar);

            final LinearLayout eventBody = new LinearLayout(getContext());
            eventBody.setOrientation(LinearLayout.VERTICAL);
            eventPanel.addView(eventBody);

            final View[] eventBodies = new View[4];
            final Switch[][] evSwitches = new Switch[4][7];
            final EditText[] evTextInputs = new EditText[4];
            final Button[] evCodeButtons = new Button[4];
            for (int i = 0; i < 4; i++) {
                final int fi = i;
                LinearLayout bodyPanel = new LinearLayout(getContext());
                bodyPanel.setOrientation(LinearLayout.VERTICAL);
                for (int f = 0; f < 7; f++) {
                    evSwitches[i][f] = fclSwitch(flagNames[f]);
                    evSwitches[i][f].setChecked(evFlags[i][f]);
                    bodyPanel.addView(formRow(formLabel(flagNames[f]), evSwitches[i][f]));
                }
                evTextInputs[i] = fclEditText("输出文本");
                evTextInputs[i].setText(evText[i]);
                bodyPanel.addView(formRow(formLabel("输出文本"), evTextInputs[i]));
                evCodeButtons[i] = ghostButton("键码: " + joinCodes(evCodes.get(i)));
                evCodeButtons[i].setOnClickListener(v -> openKeycodePicker(
                        evCodes.get(fi),
                        () -> evCodeButtons[fi].setText("键码: " + joinCodes(evCodes.get(fi)))));
                bodyPanel.addView(formRow(formLabel("键码"), evCodeButtons[i]));
                eventBodies[i] = bodyPanel;
            }
            eventBody.addView(eventBodies[0]);
            for (int i = 0; i < 4; i++) {
                final int fi = i;
                eventTabButtons[i].setOnClickListener(v -> {
                    eventBody.removeAllViews();
                    eventBody.addView(eventBodies[fi]);
                    for (int k = 0; k < 4; k++) {
                        eventTabButtons[k].setBackground(roundedDrawable(
                                k == fi ? 0xFF2196F3 : 0x22000000, dp(10)));
                        eventTabButtons[k].setTextColor(k == fi ? 0xFFFFFFFF : 0xFF2196F3);
                    }
                });
            }

            content.addView(infoPanel);
            content.addView(eventPanel);
            eventPanel.setVisibility(GONE);
            infoTab.setOnClickListener(v -> {
                infoPanel.setVisibility(VISIBLE);
                eventPanel.setVisibility(GONE);
                infoTab.setBackground(roundedDrawable(0xFF2196F3, dp(10)));
                infoTab.setTextColor(0xFFFFFFFF);
                eventTab.setBackground(roundedDrawable(0x00000000, dp(10)));
                eventTab.setTextColor(0xFF2196F3);
            });
            eventTab.setOnClickListener(v -> {
                infoPanel.setVisibility(GONE);
                eventPanel.setVisibility(VISIBLE);
                eventTab.setBackground(roundedDrawable(0xFF2196F3, dp(10)));
                eventTab.setTextColor(0xFFFFFFFF);
                infoTab.setBackground(roundedDrawable(0x00000000, dp(10)));
                infoTab.setTextColor(0xFF2196F3);
            });

            // ---- bottom actions: FCL layout 克隆/删除 left, 取消/确定 right ----
            LinearLayout bottom = new LinearLayout(getContext());
            bottom.setOrientation(LinearLayout.HORIZONTAL);
            bottom.setGravity(Gravity.CENTER_VERTICAL);
            bottom.setPadding(0, dp(8), 0, 0);
            Button cloneBtn = ghostButton("克隆");
            Button delBtn = ghostButton("删除");
            Button cancelBtn = ghostButton("取消");
            Button okBtn = ghostButton("确定");
            bottom.addView(cloneBtn);
            bottom.addView(delBtn);
            View spacer = new View(getContext());
            spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1f));
            bottom.addView(spacer);
            bottom.addView(cancelBtn);
            bottom.addView(okBtn);
            root.addView(bottom);

            android.app.Dialog dialog = new android.app.Dialog(getContext());
            int screenW = getResources().getDisplayMetrics().widthPixels;
            int screenH = getResources().getDisplayMetrics().heightPixels;
            int dialogW = Math.min(dp(500), screenW - dp(24));
            int dialogH = (int) (screenH * 0.88f);
            dialog.setContentView(root, new android.view.ViewGroup.LayoutParams(
                    dialogW, dialogH));
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(0));
                dialog.getWindow().setGravity(Gravity.CENTER);
                dialog.getWindow().setLayout(dialogW, dialogH);
            }
            dialog.setOnShowListener(d -> {
                if (dialog.getWindow() != null) {
                    android.view.WindowManager.LayoutParams attrs =
                            dialog.getWindow().getAttributes();
                    attrs.gravity = Gravity.TOP | Gravity.START;
                    attrs.x = (screenW - dialogW) / 2;
                    attrs.y = (screenH - dialogH) / 2;
                    attrs.width = dialogW;
                    attrs.height = dialogH;
                    dialog.getWindow().setAttributes(attrs);
                }
            });
            okBtn.setOnClickListener(v -> {
                for (int i = 0; i < 4; i++) {
                    evText[i] = evTextInputs[i].getText().toString();
                    for (int f = 0; f < 7; f++) {
                        evFlags[i][f] = evSwitches[i][f].isChecked();
                    }
                }
                savePropertyJson(
                        textInput.getText().toString(),
                        xInput.getText().toString(),
                        yInput.getText().toString(),
                        sizeSpinner.getSelectedItemPosition(),
                        refSpinner.getSelectedItemPosition(),
                        sizeInput.getText().toString(),
                        styleSpinner.getSelectedItem().toString(),
                        pointerSwitch.isChecked(),
                        dragSwitch.isChecked(),
                        movableSwitch.isChecked(),
                        evFlags, evText, evCodes);
                dialog.dismiss();
            });
            cancelBtn.setOnClickListener(v -> dialog.dismiss());
            cloneBtn.setOnClickListener(v -> {
                cloneButtonJson(btn);
                dialog.dismiss();
            });
            delBtn.setOnClickListener(v -> {
                deleteButtonJson(btn);
                dialog.dismiss();
            });
            showOverlayDialog(dialog);
        }

        private void savePropertyJson(String text, String xs, String ys,
                                      int sizeIdx, int refIdx, String sizeVal,
                                      String style, boolean pointerFollow,
                                      boolean dragMove, boolean movable,
                                      boolean[][] flags, String[] texts,
                                      List<List<Integer>> codes) {
            JSONObject btn = controller.findControlJson(data.id);
            if (btn == null) {
                return;
            }
            try {
                JSONObject base = btn.optJSONObject("baseInfo");
                if (base == null) {
                    base = new JSONObject();
                    btn.put("baseInfo", base);
                }
                JSONObject ev = btn.optJSONObject("event");
                if (ev == null) {
                    ev = new JSONObject();
                    btn.put("event", ev);
                }
                btn.put("text", text);
                btn.put("style", style);
                // Our port has no cursor-mode concept, so controls are always shown.
                base.put("visibilityType", "ALWAYS");
                base.put("xPosition", parseIntClamped(xs, 0, 1000, data.baseInfo.xPosition));
                base.put("yPosition", parseIntClamped(ys, 0, 1000, data.baseInfo.yPosition));
                boolean abs = sizeIdx == 1;
                base.put("sizeType", abs ? "ABSOLUTE" : "PERCENTAGE");
                String reference = refIdx == 0 ? "SCREEN_WIDTH" : "SCREEN_HEIGHT";
                int size = parseIntClamped(sizeVal, 0, abs ? 2000 : 1000, 120);
                if (abs) {
                    base.put("absoluteWidth", size);
                    base.put("absoluteHeight", size);
                } else {
                    base.put("percentageWidth", new JSONObject()
                            .put("reference", reference).put("size", size));
                    base.put("percentageHeight", new JSONObject()
                            .put("reference", reference).put("size", size));
                }
                ev.put("pointerFollow", pointerFollow);
                ev.put("dragMoveMouse", dragMove);
                ev.put("Movable", movable);
                final String[] eventKeys = {"pressEvent", "longPressEvent",
                        "clickEvent", "doubleClickEvent"};
                final String[] flagKeys = {"autoKeep", "autoClick", "openMenu",
                        "switchTouchMode", "switchMouseMode", "input", "quickInput"};
                for (int i = 0; i < 4; i++) {
                    JSONObject e = new JSONObject();
                    for (int f = 0; f < 7; f++) {
                        e.put(flagKeys[f], flags[i][f]);
                    }
                    e.put("outputText", texts[i]);
                    JSONArray arr = new JSONArray();
                    for (int c : codes.get(i)) {
                        arr.put(c);
                    }
                    e.put("outputKeycodes", arr);
                    ev.put(eventKeys[i], e);
                }
                btn.put("event", ev);
                applyPendingPositionsToJson();
                if (controller.saveToFile(getContext())) {
                    reloadController();
                } else {
                    Toast.makeText(getContext(), "保存失败", Toast.LENGTH_SHORT).show();
                }
            } catch (JSONException e) {
                Toast.makeText(getContext(), "保存失败: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
        }


        private void cloneButtonJson(JSONObject btn) {
            try {
                JSONObject copy = new JSONObject(btn.toString());
                copy.put("id", String.format(java.util.Locale.US, "%08x",
                        new java.util.Random().nextInt(0x10000000)));
                JSONObject group = groupJsonForControl(data.id);
                if (group == null) {
                    return;
                }
                JSONObject vd = group.optJSONObject("viewData");
                if (vd == null) {
                    vd = new JSONObject();
                    group.put("viewData", vd);
                }
                JSONArray bl = vd.optJSONArray("buttonList");
                if (bl == null) {
                    bl = new JSONArray();
                    vd.put("buttonList", bl);
                }
                bl.put(copy);
                if (controller.saveToFile(getContext())) {
                    reloadController();
                }
            } catch (JSONException e) {
                Toast.makeText(getContext(), "克隆失败: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
        }

        private void deleteButtonJson(JSONObject btn) {
            JSONObject group = groupJsonForControl(data.id);
            if (group == null) {
                return;
            }
            JSONObject vd = group.optJSONObject("viewData");
            JSONArray bl = vd != null ? vd.optJSONArray("buttonList") : null;
            if (bl == null) {
                return;
            }
            for (int i = 0; i < bl.length(); i++) {
                JSONObject b = bl.optJSONObject(i);
                if (b != null && data.id.equals(b.optString("id"))) {
                    bl.remove(i);
                    break;
                }
            }
            if (controller.saveToFile(getContext())) {
                reloadController();
            }
        }



        private void trigger(FclController.Event ev, boolean enable, boolean clickType,
                             int eventType) {
            if (ev == null || !enable) {
                return;
            }
            if (ev.autoKeep) {
                if (keepActive[eventType]) {
                    // Already latched: this press releases it again (toggle off).
                    keepActive[eventType] = false;
                    if (ev.autoClick) {
                        stopAutoClick();
                    } else {
                        keyUp(ev);
                    }
                } else {
                    keepActive[eventType] = true;
                    if (ev.autoClick) {
                        startAutoClick(ev);
                    } else {
                        keyDown(ev);
                    }
                }
            } else if (ev.autoClick) {
                startAutoClick(ev);
            } else if (clickType) {
                keyDown(ev);
                keyUp(ev);
            } else {
                keyDown(ev);
            }
            sideEffects(ev);
        }

        private void releaseEvent(FclController.Event ev, boolean force, int eventType) {
            if (ev == null) {
                return;
            }
            if (force || !ev.autoKeep) {
                if (ev.autoClick) {
                    stopAutoClick();
                }
                // For a latched event only send the release when it is actually
                // held; otherwise a force-cleanup could lift a key that another
                // control is still pressing.
                if (!ev.autoKeep || keepActive[eventType]) {
                    keyUp(ev);
                }
            }
            if (force) {
                keepActive[eventType] = false;
            }
        }

        private boolean anyKeepActive() {
            return keepActive[EVENT_PRESS] || keepActive[EVENT_LONG_PRESS]
                    || keepActive[EVENT_CLICK] || keepActive[EVENT_DOUBLE_CLICK];
        }

        private void sideEffects(FclController.Event ev) {
            if (ev.openMenu) {
                bridge.openSettings();
            }
            if (ev.input || ev.quickInput) {
                bridge.toggleIme();
            }
            if (ev.outputText != null && !ev.outputText.isEmpty()) {
                bridge.text(ev.outputText);
            }
            for (String groupId : ev.bindViewGroup) {
                toggleGroup(groupId);
            }
        }

        private void startAutoClick(FclController.Event ev) {
            if (autoClickRunning) {
                return;
            }
            autoClickEvent = ev;
            autoClickRunning = true;
            handler.post(autoClickRunnable);
        }

        private void stopAutoClick() {
            autoClickRunning = false;
            handler.removeCallbacks(autoClickRunnable);
        }

        private void keyDown(FclController.Event ev) {
            for (int code : ev.outputKeycodes) {
                sendFclKey(code, 0);
            }
        }

        private void keyUp(FclController.Event ev) {
            for (int code : ev.outputKeycodes) {
                sendFclKey(code, 1);
            }
        }

        void releaseAll() {
            handler.removeCallbacks(longPressRunnable);
            stopAutoClick();
            if (longPressFired) {
                releaseEvent(data.longPressEvent, true, EVENT_LONG_PRESS);
                longPressFired = false;
            }
            releaseEvent(data.pressEvent, true, EVENT_PRESS);
            releaseEvent(data.clickEvent, true, EVENT_CLICK);
            releaseEvent(data.doubleClickEvent, true, EVENT_DOUBLE_CLICK);
            pressed = false;
            clickCount = 0;
            invalidate();
        }

        private int getParentWidth() {
            return FclControllerView.this.getWidth();
        }

        private int getParentHeight() {
            return FclControllerView.this.getHeight();
        }
    }

    // ======================================================================
    // Direction pad / rocker
    // ======================================================================

    private final class FclDirectionView extends View {
        private final FclController.Direction data;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        private int rockerSize;
        private int maxDistance;
        private float rockerOffsetX;
        private float rockerOffsetY;

        private boolean dirUp, dirDown, dirLeft, dirRight;
        private boolean sneakActive = false;
        private boolean startClick = false;
        private boolean moved = false;
        private float downX, downY;
        private long downTime;
        private int clickCount = 0;
        private long firstClickTime;

        FclDirectionView(Context context, FclController.Direction data) {
            super(context);
            this.data = data;
            setClickable(true);
            setWillNotDraw(false);
            strokePaint.setStyle(Paint.Style.STROKE);
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if ("ROCKER".equals(data.style.styleType) && data.style.rockerStyle != null) {
                rockerSize = w * data.style.rockerStyle.rockerSize / 1000;
                maxDistance = Math.max(0, w / 2 - rockerSize / 2);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if ("BUTTON".equals(data.style.styleType)) {
                drawPad(canvas);
            } else {
                drawRocker(canvas);
            }
            if (editMode) {
                float eb = dp(2);
                rect.set(eb, eb, getWidth() - eb, getHeight() - eb);
                strokePaint.setColor(0xFFFF4444);
                strokePaint.setStrokeWidth(eb);
                canvas.drawRoundRect(rect, eb, eb, strokePaint);
            }
        }

        private void drawRocker(Canvas canvas) {
            FclController.RockerStyle rs = data.style.rockerStyle;
            if (rs == null) {
                return;
            }
            int w = getWidth();
            int h = getHeight();
            float bgStroke = dp(rs.bgStrokeWidth / 10f);
            float bgCorner = w * rs.bgCornerRadius / 1000f;
            rect.set(bgStroke, bgStroke, w - bgStroke, h - bgStroke);
            fillPaint.setColor(rs.bgFillColor);
            strokePaint.setColor(rs.bgStrokeColor);
            strokePaint.setStrokeWidth(bgStroke);
            canvas.drawRoundRect(rect, bgCorner, bgCorner, fillPaint);
            canvas.drawRoundRect(rect, bgCorner, bgCorner, strokePaint);

            if (rockerSize <= 0) {
                rockerSize = w * rs.rockerSize / 1000;
                maxDistance = Math.max(0, w / 2 - rockerSize / 2);
            }
            float cx = w / 2f + rockerOffsetX;
            float cy = h / 2f + rockerOffsetY;
            float rStroke = dp(rs.rockerStrokeWidth / 10f);
            float rCorner = rockerSize * rs.rockerCornerRadius / 1000f;
            rect.set(cx - rockerSize / 2f, cy - rockerSize / 2f,
                    cx + rockerSize / 2f, cy + rockerSize / 2f);
            fillPaint.setColor(rs.rockerFillColor);
            strokePaint.setColor(rs.rockerStrokeColor);
            strokePaint.setStrokeWidth(rStroke);
            canvas.drawRoundRect(rect, rCorner, rCorner, fillPaint);
            canvas.drawRoundRect(rect, rCorner, rCorner, strokePaint);
        }

        private void drawPad(Canvas canvas) {
            FclController.ButtonStyle bs = data.style.buttonStyle;
            if (bs == null) {
                return;
            }
            int w = getWidth();
            int size = w * (1000 - 2 * (int) bs.strokeWidth) / 3000;
            int p0 = 0;
            int p1 = size + w * (int) bs.strokeWidth / 1000;
            int p2 = w - size;
            drawPadKey(canvas, bs, p1, p0, size, "▲", dirUp);
            drawPadKey(canvas, bs, p0, p1, size, "◀", dirLeft);
            drawPadKey(canvas, bs, p1, p1, size, "◆", !dirUp && !dirDown && !dirLeft && !dirRight);
            drawPadKey(canvas, bs, p2, p1, size, "▶", dirRight);
            drawPadKey(canvas, bs, p1, p2, size, "▼", dirDown);
        }

        private void drawPadKey(Canvas canvas, FclController.ButtonStyle bs,
                                int x, int y, int size, String text, boolean active) {
            float stroke = dp((active ? bs.strokeWidthPressed : bs.strokeWidth) / 10f);
            float corner = dp((active ? bs.cornerRadiusPressed : bs.cornerRadius) / 10f);
            rect.set(x + stroke, y + stroke, x + size - stroke, y + size - stroke);
            fillPaint.setColor(active ? bs.fillColorPressed : bs.fillColor);
            strokePaint.setColor(active ? bs.strokeColorPressed : bs.strokeColor);
            strokePaint.setStrokeWidth(stroke);
            canvas.drawRoundRect(rect, corner, corner, fillPaint);
            canvas.drawRoundRect(rect, corner, corner, strokePaint);
            textPaint.setColor(active ? bs.textColorPressed : bs.textColor);
            textPaint.setTextSize((active ? bs.textSizePressed : bs.textSize) * density);
            canvas.drawText(text, x + size / 2f, y + size / 2f
                    - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (editMode) {
                return handleEditTouch(event);
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    downTime = System.currentTimeMillis();
                    moved = false;
                    startClick = false;
                    if ("ROCKER".equals(data.style.styleType)
                            && ("FOLLOW".equals(data.followOption)
                            || ("CENTER_FOLLOW".equals(data.followOption)
                            && insideRocker(event.getX(), event.getY())))) {
                        startClick = true;
                    }
                    handlePadEvent(event.getX(), event.getY());
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getX() - downX) > 10
                            || Math.abs(event.getY() - downY) > 10) {
                        moved = true;
                        startClick = false;
                    }
                    handlePadEvent(event.getX(), event.getY());
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    boolean tap = !moved && System.currentTimeMillis() - downTime <= 100;
                    if (tap && startClick && data.sneak) {
                        clickCount++;
                        if (clickCount == 1) {
                            firstClickTime = System.currentTimeMillis();
                        } else if (clickCount == 2) {
                            if (System.currentTimeMillis() - firstClickTime < 400) {
                                toggleSneak();
                            }
                            clickCount = 0;
                        }
                    }
                    setDirs(false, false, false, false);
                    rockerOffsetX = 0;
                    rockerOffsetY = 0;
                    invalidate();
                    return true;
            }
            return true;
        }

        private boolean handleEditTouch(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (Math.abs(dx) > 2 || Math.abs(dy) > 2) {
                        moved = true;
                    }
                    float nx = clamp(getX() + dx, 0,
                            FclControllerView.this.getWidth() - getWidth());
                    float ny = clamp(getY() + dy, 0,
                            FclControllerView.this.getHeight() - getHeight());
                    setX(nx);
                    setY(ny);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (moved) {
                        int pw = FclControllerView.this.getWidth();
                        int ph = FclControllerView.this.getHeight();
                        int xTh = pw > getWidth()
                                ? Math.round(1000f * getX() / (pw - getWidth())) : 0;
                        int yTh = ph > getHeight()
                                ? Math.round(1000f * getY() / (ph - getHeight())) : 0;
                        setPendingPosition(data.id, xTh, yTh);
                    } else {
                        openDirectionDialog();
                    }
                    return true;
            }
            return true;
        }

        /** FCL-style direction editor: position/size/style + direction keycodes. */
        private void openDirectionDialog() {
            if (controller == null) {
                return;
            }
            final JSONObject dir = controller.findControlJson(data.id);
            if (dir == null) {
                return;
            }
            final int pad = dp(12);
            LinearLayout root = new LinearLayout(getContext());
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(pad, pad, pad, pad);
            root.setBackground(roundedDrawable(0xFFF5F5F5, dp(16)));

            TextView titleView = new TextView(getContext());
            titleView.setText("编辑方向控件");
            titleView.setTextSize(17);
            titleView.setTypeface(null, Typeface.BOLD);
            titleView.setTextColor(0xFF212121);
            titleView.setPadding(0, 0, 0, dp(8));
            root.addView(titleView);

            EditText xInput = new EditText(getContext());
            xInput.setHint("X位置（千分比 0-1000）");
            xInput.setInputType(InputType.TYPE_CLASS_NUMBER);
            xInput.setText(String.valueOf(data.baseInfo.xPosition));
            root.addView(xInput);

            EditText yInput = new EditText(getContext());
            yInput.setHint("Y位置（千分比 0-1000）");
            yInput.setInputType(InputType.TYPE_CLASS_NUMBER);
            yInput.setText(String.valueOf(data.baseInfo.yPosition));
            root.addView(yInput);

            Spinner sizeSpinner = new Spinner(getContext());
            String[] sizeNames = {"百分比", "绝对dp"};
            sizeSpinner.setAdapter(new ArrayAdapter<>(getContext(),
                    android.R.layout.simple_spinner_dropdown_item, sizeNames));
            sizeSpinner.setSelection("ABSOLUTE".equals(data.baseInfo.sizeType) ? 1 : 0);
            root.addView(sizeSpinner);

            Spinner refSpinner = new Spinner(getContext());
            String[] refNames = {"参照屏宽", "参照屏高"};
            refSpinner.setAdapter(new ArrayAdapter<>(getContext(),
                    android.R.layout.simple_spinner_dropdown_item, refNames));
            refSpinner.setSelection("SCREEN_WIDTH"
                    .equals(data.baseInfo.percentageWidth.reference) ? 0 : 1);
            root.addView(refSpinner);

            EditText sizeInput = new EditText(getContext());
            sizeInput.setHint("尺寸（百分比 0-1000 或 dp）");
            sizeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
            int sizeVal = "ABSOLUTE".equals(data.baseInfo.sizeType)
                    ? data.baseInfo.absoluteWidth
                    : data.baseInfo.percentageWidth.size;
            sizeInput.setText(String.valueOf(sizeVal));
            root.addView(sizeInput);

            Spinner styleSpinner = new Spinner(getContext());
            List<String> styleNames = new ArrayList<>(controller.directionStylesByName.keySet());
            if (styleNames.isEmpty()) {
                styleNames.add("Default");
            }
            styleSpinner.setAdapter(new ArrayAdapter<>(getContext(),
                    android.R.layout.simple_spinner_dropdown_item, styleNames));
            int styleIdx = styleNames.indexOf(dir.optString("style", "Default"));
            styleSpinner.setSelection(styleIdx < 0 ? 0 : styleIdx);
            root.addView(styleSpinner);

            Spinner followSpinner = new Spinner(getContext());
            String[] followNames = {"固定", "中心跟随", "跟随"};
            followSpinner.setAdapter(new ArrayAdapter<>(getContext(),
                    android.R.layout.simple_spinner_dropdown_item, followNames));
            String follow = data.followOption;
            followSpinner.setSelection("FIXED".equals(follow) ? 0
                    : ("CENTER_FOLLOW".equals(follow) ? 1 : 2));
            root.addView(followSpinner);

            final List<Integer> upCodes = new ArrayList<>();
            final List<Integer> downCodes = new ArrayList<>();
            final List<Integer> leftCodes = new ArrayList<>();
            final List<Integer> rightCodes = new ArrayList<>();
            final List<Integer> sneakCodes = new ArrayList<>();
            addAll(upCodes, data.upKeycodes);
            addAll(downCodes, data.downKeycodes);
            addAll(leftCodes, data.leftKeycodes);
            addAll(rightCodes, data.rightKeycodes);
            sneakCodes.add(data.sneakKeycode);

            Button upBtn = new Button(getContext());
            upBtn.setText("上键码: " + joinCodes(upCodes));
            upBtn.setOnClickListener(v -> openKeycodePicker(upCodes,
                    () -> upBtn.setText("上键码: " + joinCodes(upCodes))));
            root.addView(upBtn);

            Button downBtn = new Button(getContext());
            downBtn.setText("下键码: " + joinCodes(downCodes));
            downBtn.setOnClickListener(v -> openKeycodePicker(downCodes,
                    () -> downBtn.setText("下键码: " + joinCodes(downCodes))));
            root.addView(downBtn);

            Button leftBtn = new Button(getContext());
            leftBtn.setText("左键码: " + joinCodes(leftCodes));
            leftBtn.setOnClickListener(v -> openKeycodePicker(leftCodes,
                    () -> leftBtn.setText("左键码: " + joinCodes(leftCodes))));
            root.addView(leftBtn);

            Button rightBtn = new Button(getContext());
            rightBtn.setText("右键码: " + joinCodes(rightCodes));
            rightBtn.setOnClickListener(v -> openKeycodePicker(rightCodes,
                    () -> rightBtn.setText("右键码: " + joinCodes(rightCodes))));
            root.addView(rightBtn);

            Switch sneakSwitch = new Switch(getContext());
            sneakSwitch.setText("双击潜行");
            sneakSwitch.setChecked(data.sneak);
            root.addView(sneakSwitch);

            Button sneakKeyBtn = new Button(getContext());
            sneakKeyBtn.setText("潜行键码: " + joinCodes(sneakCodes));
            sneakKeyBtn.setOnClickListener(v -> openKeycodePicker(sneakCodes,
                    () -> sneakKeyBtn.setText("潜行键码: " + joinCodes(sneakCodes))));
            root.addView(sneakKeyBtn);

            LinearLayout bottom = new LinearLayout(getContext());
            bottom.setOrientation(LinearLayout.HORIZONTAL);
            Button okBtn = ghostButton("确定");
            Button cancelBtn = ghostButton("取消");
            Button cloneBtn = ghostButton("克隆");
            Button delBtn = ghostButton("删除");
            bottom.addView(okBtn);
            bottom.addView(cancelBtn);
            bottom.addView(cloneBtn);
            bottom.addView(delBtn);
            root.addView(bottom);

            ScrollView scroll = new ScrollView(getContext());
            scroll.addView(root);
            android.app.Dialog dialog = new android.app.Dialog(getContext());
            int screenW = getResources().getDisplayMetrics().widthPixels;
            int screenH = getResources().getDisplayMetrics().heightPixels;
            int dialogW = Math.min(dp(500), screenW - dp(24));
            int dialogH = (int) (screenH * 0.88f);
            dialog.setContentView(scroll, new android.view.ViewGroup.LayoutParams(
                    dialogW, dialogH));
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(0));
                dialog.getWindow().setGravity(Gravity.CENTER);
                dialog.getWindow().setLayout(dialogW, dialogH);
            }
            dialog.setOnShowListener(d -> {
                if (dialog.getWindow() != null) {
                    android.view.WindowManager.LayoutParams attrs =
                            dialog.getWindow().getAttributes();
                    attrs.gravity = Gravity.TOP | Gravity.START;
                    attrs.x = (screenW - dialogW) / 2;
                    attrs.y = (screenH - dialogH) / 2;
                    attrs.width = dialogW;
                    attrs.height = dialogH;
                    dialog.getWindow().setAttributes(attrs);
                }
            });
            okBtn.setOnClickListener(v -> {
                saveDirectionJson(
                        xInput.getText().toString(),
                        yInput.getText().toString(),
                        sizeSpinner.getSelectedItemPosition(),
                        refSpinner.getSelectedItemPosition(),
                        sizeInput.getText().toString(),
                        styleSpinner.getSelectedItem().toString(),
                        followSpinner.getSelectedItemPosition(),
                        upCodes, downCodes, leftCodes, rightCodes,
                        sneakSwitch.isChecked(), sneakCodes);
                dialog.dismiss();
            });
            cancelBtn.setOnClickListener(v -> dialog.dismiss());
            cloneBtn.setOnClickListener(v -> {
                cloneDirectionJson(dir);
                dialog.dismiss();
            });
            delBtn.setOnClickListener(v -> {
                deleteDirectionJson(dir);
                dialog.dismiss();
            });
            showOverlayDialog(dialog);
        }

        private void saveDirectionJson(String xs, String ys, int sizeIdx, int refIdx,
                                       String sizeVal, String style, int followIdx,
                                       List<Integer> up, List<Integer> down,
                                       List<Integer> left, List<Integer> right,
                                       boolean sneak, List<Integer> sneakCodes) {
            JSONObject dir = controller.findControlJson(data.id);
            if (dir == null) {
                return;
            }
            try {
                JSONObject base = dir.optJSONObject("baseInfo");
                if (base == null) {
                    base = new JSONObject();
                    dir.put("baseInfo", base);
                }
                JSONObject ev = dir.optJSONObject("event");
                if (ev == null) {
                    ev = new JSONObject();
                    dir.put("event", ev);
                }
                dir.put("style", style);
                base.put("visibilityType", "ALWAYS");
                base.put("xPosition", parseIntClamped(xs, 0, 1000, data.baseInfo.xPosition));
                base.put("yPosition", parseIntClamped(ys, 0, 1000, data.baseInfo.yPosition));
                boolean abs = sizeIdx == 1;
                base.put("sizeType", abs ? "ABSOLUTE" : "PERCENTAGE");
                String reference = refIdx == 0 ? "SCREEN_WIDTH" : "SCREEN_HEIGHT";
                int size = parseIntClamped(sizeVal, 0, abs ? 2000 : 1000, 450);
                if (abs) {
                    base.put("absoluteWidth", size);
                    base.put("absoluteHeight", size);
                } else {
                    base.put("percentageWidth", new JSONObject()
                            .put("reference", reference).put("size", size));
                    base.put("percentageHeight", new JSONObject()
                            .put("reference", reference).put("size", size));
                }
                ev.put("upKeycode", toKeycodeArray(up));
                ev.put("downKeycode", toKeycodeArray(down));
                ev.put("leftKeycode", toKeycodeArray(left));
                ev.put("rightKeycode", toKeycodeArray(right));
                ev.put("followOption", followIdx == 0 ? "FIXED"
                        : (followIdx == 1 ? "CENTER_FOLLOW" : "FOLLOW"));
                ev.put("sneak", sneak);
                ev.put("sneakKeycode", sneakCodes.isEmpty() ? 42 : sneakCodes.get(0));
                dir.put("event", ev);
                applyPendingPositionsToJson();
                if (controller.saveToFile(getContext())) {
                    reloadController();
                } else {
                    Toast.makeText(getContext(), "保存失败", Toast.LENGTH_SHORT).show();
                }
            } catch (JSONException e) {
                Toast.makeText(getContext(), "保存失败: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
        }

        private void cloneDirectionJson(JSONObject dir) {
            try {
                JSONObject copy = new JSONObject(dir.toString());
                copy.put("id", String.format(java.util.Locale.US, "%08x",
                        new java.util.Random().nextInt(0x10000000)));
                JSONObject group = groupJsonForControl(data.id);
                if (group == null) {
                    return;
                }
                JSONObject vd = group.optJSONObject("viewData");
                if (vd == null) {
                    vd = new JSONObject();
                    group.put("viewData", vd);
                }
                JSONArray dl = vd.optJSONArray("directionList");
                if (dl == null) {
                    dl = new JSONArray();
                    vd.put("directionList", dl);
                }
                dl.put(copy);
                if (controller.saveToFile(getContext())) {
                    reloadController();
                }
            } catch (JSONException e) {
                Toast.makeText(getContext(), "克隆失败: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
        }

        private void deleteDirectionJson(JSONObject dir) {
            JSONObject group = groupJsonForControl(data.id);
            if (group == null) {
                return;
            }
            JSONObject vd = group.optJSONObject("viewData");
            JSONArray dl = vd != null ? vd.optJSONArray("directionList") : null;
            if (dl == null) {
                return;
            }
            for (int i = 0; i < dl.length(); i++) {
                JSONObject d = dl.optJSONObject(i);
                if (d != null && data.id.equals(d.optString("id"))) {
                    dl.remove(i);
                    break;
                }
            }
            if (controller.saveToFile(getContext())) {
                reloadController();
            }
        }

        private void addAll(List<Integer> target, int[] codes) {
            if (codes != null) {
                for (int c : codes) {
                    target.add(c);
                }
            }
        }

        private JSONArray toKeycodeArray(List<Integer> codes) {
            JSONArray arr = new JSONArray();
            for (int c : codes) {
                arr.put(c);
            }
            return arr;
        }

        private boolean insideRocker(float x, float y) {
            if (rockerSize <= 0) {
                return false;
            }
            float cx = getWidth() / 2f + rockerOffsetX;
            float cy = getHeight() / 2f + rockerOffsetY;
            return x >= cx - rockerSize / 2f && x <= cx + rockerSize / 2f
                    && y >= cy - rockerSize / 2f && y <= cy + rockerSize / 2f;
        }

        private void handlePadEvent(float x, float y) {
            if ("BUTTON".equals(data.style.styleType)) {
                handleButtonEvent((int) x, (int) y);
            } else {
                handleRockerEvent((int) x, (int) y);
            }
        }

        private void handleButtonEvent(int x, int y) {
            if (data.style.buttonStyle == null) {
                return;
            }
            int w = getWidth();
            int interval = (int) data.style.buttonStyle.strokeWidth;
            int size = w * (1000 - 2 * interval) / 3000;
            int p1 = size + w * interval / 1000;
            int p2 = w - size;
            boolean up = y <= size;
            boolean down = y >= p2;
            boolean left = x <= size;
            boolean right = x >= p2;
            if (x >= p1 && x <= p1 + size && y >= p1 && y <= p1 + size) {
                up = down = left = right = false;
            }
            setDirs(up, down, left, right);
        }

        private void handleRockerEvent(int x, int y) {
            if (rockerSize <= 0 || maxDistance <= 0) {
                return;
            }
            int w = getWidth();
            int h = getHeight();
            float dx = x - w / 2f;
            float dy = y - h / 2f;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist > maxDistance) {
                dx = dx / dist * maxDistance;
                dy = dy / dist * maxDistance;
            }
            rockerOffsetX = dx;
            rockerOffsetY = dy;

            float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
            if (angle < 0) {
                angle += 360f;
            }
            boolean up = angle >= 202.5f && angle < 292.5f;
            boolean down = angle >= 22.5f && angle < 157.5f;
            boolean left = angle >= 157.5f && angle < 202.5f;
            boolean right = angle >= 292.5f || angle < 22.5f;
            // Corner sectors: FCL treats them as the two adjacent directions.
            boolean upLeft = angle >= 202.5f && angle < 247.5f;
            boolean upRight = angle >= 292.5f && angle < 337.5f;
            boolean downLeft = angle >= 112.5f && angle < 157.5f;
            boolean downRight = angle >= 22.5f && angle < 67.5f;
            if (upLeft) {
                up = left = true;
            } else if (upRight) {
                up = right = true;
            } else if (downLeft) {
                down = left = true;
            } else if (downRight) {
                down = right = true;
            }
            setDirs(up, down, left, right);
            invalidate();
        }

        private void setDirs(boolean up, boolean down, boolean left, boolean right) {
            if (up != dirUp) {
                dirUp = up;
                sendDirCodes(data.upKeycodes, up);
            }
            if (down != dirDown) {
                dirDown = down;
                sendDirCodes(data.downKeycodes, down);
            }
            if (left != dirLeft) {
                dirLeft = left;
                sendDirCodes(data.leftKeycodes, left);
            }
            if (right != dirRight) {
                dirRight = right;
                sendDirCodes(data.rightKeycodes, right);
            }
            invalidate();
        }

        private void sendDirCodes(int[] codes, boolean press) {
            for (int code : codes) {
                sendFclKey(code, press ? 0 : 1);
            }
        }

        private void toggleSneak() {
            sneakActive = !sneakActive;
            sendFclKey(data.sneakKeycode, sneakActive ? 0 : 1);
        }

        void releaseAll() {
            setDirs(false, false, false, false);
            if (sneakActive) {
                sneakActive = false;
                sendFclKey(data.sneakKeycode, 1);
            }
            rockerOffsetX = 0;
            rockerOffsetY = 0;
            clickCount = 0;
            invalidate();
        }
    }

    // ======================================================================

    private void sendFclKey(int code, int action) {
        if (bridge == null) {
            return;
        }
        // FCL uses -1/0 for "no key" in some community controllers.
        if (code <= 0) {
            return;
        }
        boolean down = action == 0;
        if (code == FCL_MOUSE_LEFT) {
            bridge.mouseButton(EV_BTN_LEFT, down);
        } else if (code == FCL_MOUSE_MIDDLE) {
            bridge.mouseButton(EV_BTN_MIDDLE, down);
        } else if (code == FCL_MOUSE_RIGHT) {
            bridge.mouseButton(EV_BTN_RIGHT, down);
        } else if (code == FCL_MOUSE_SCROLL_UP) {
            bridge.mouseScroll(0, down ? 10f : 0f, down ? 1 : 0);
        } else if (code == FCL_MOUSE_SCROLL_DOWN) {
            bridge.mouseScroll(0, down ? -10f : 0f, down ? -1 : 0);
        } else {
            bridge.key(action, code);
        }
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

}
