package com.anland.consumer;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
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
import android.widget.Switch;
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
    // Controller management toolbar (编辑 / 新增 / 删除), rebuilt with the controls.
    private Button editButton;
    private Button addButton;
    private Button deleteButton;

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
        if (editButton != null) {
            editButton.setText(editMode ? "完成" : "编辑");
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
        buildToolbar();
        requestLayout();
        postInvalidate();
    }

    /** Top-right management toolbar: 编辑 / 新增 / 删除. */
    private void buildToolbar() {
        removeToolbarIfAttached();
        editButton = new Button(getContext());
        addButton = new Button(getContext());
        deleteButton = new Button(getContext());
        editButton.setText(editMode ? "完成" : "编辑");
        addButton.setText("新增");
        deleteButton.setText("删除");
        for (Button b : new Button[]{editButton, addButton, deleteButton}) {
            b.setTextSize(11);
            b.setAllCaps(false);
            b.setPadding(dp(8), dp(2), dp(8), dp(2));
            b.setBackgroundColor(0x99000000);
            b.setTextColor(0xFFFFFFFF);
            addView(b, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.START));
        }
        // Chain the three buttons along the top edge once they are measured.
        post(() -> {
            if (deleteButton == null || deleteButton.getMeasuredWidth() <= 0) {
                return;
            }
            int gap = dp(4);
            int right = getWidth() - dp(8);
            int x = right - deleteButton.getMeasuredWidth();
            deleteButton.setX(x);
            x -= deleteButton.getMeasuredWidth() + gap;
            addButton.setX(x);
            x -= addButton.getMeasuredWidth() + gap;
            editButton.setX(x);
            int y = dp(8);
            deleteButton.setY(y);
            addButton.setY(y);
            editButton.setY(y);
        });
        editButton.setOnClickListener(v -> {
            if (editMode) {
                promptExitEditMode();
            } else {
                setEditMode(true);
            }
        });
        addButton.setOnClickListener(v -> promptAddController());
        deleteButton.setOnClickListener(v -> promptDeleteController());
    }

    private void removeToolbarIfAttached() {
        if (editButton != null && editButton.getParent() == this) {
            removeView(editButton);
        }
        if (addButton != null && addButton.getParent() == this) {
            removeView(addButton);
        }
        if (deleteButton != null && deleteButton.getParent() == this) {
            removeView(deleteButton);
        }
    }

    /** Save pending drag positions and per-key edits into the controller file. */
    public void saveEdit() {
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
        controller.saveToFile(getContext());
        reloadController();
    }

    /** Back / 完成 in edit mode: ask whether to keep the changes. */
    public void promptExitEditMode() {
        if (!editMode) {
            return;
        }
        new AlertDialog.Builder(getContext())
                .setTitle("保存修改？")
                .setMessage("是否保存对控制器的修改？")
                .setPositiveButton("保存", (d, w) -> saveEdit())
                .setNegativeButton("不保存", (d, w) -> discardPositions())
                .setNeutralButton("取消", null)
                .show();
    }

    private void promptAddController() {
        EditText nameInput = new EditText(getContext());
        nameInput.setHint("控制器名称");
        LinearLayout wrap = new LinearLayout(getContext());
        wrap.setPadding(dp(24), dp(8), dp(24), 0);
        wrap.addView(nameInput);
        new AlertDialog.Builder(getContext())
                .setTitle("新增控制器")
                .setView(wrap)
                .setPositiveButton("创建", (d, w) -> {
                    String newId = FclController.createCopy(getContext(), controller,
                            nameInput.getText().toString());
                    if (newId != null) {
                        bridge.selectController(newId);
                        Toast.makeText(getContext(), "已创建控制器", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(getContext(), "创建失败", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void promptDeleteController() {
        if (controller == null) {
            return;
        }
        new AlertDialog.Builder(getContext())
                .setTitle("删除控制器")
                .setMessage("删除 “" + controller.name + "”？")
                .setPositiveButton("删除", (d, w) -> {
                    if (controller.isBundled(getContext())) {
                        Toast.makeText(getContext(), "内置控制器不能删除",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        FclController.deleteFromDisk(getContext(), controller.id);
                        bridge.selectController(null);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
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
            setEditMode(false);
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
            child.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
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

        /** Edit this key's text, keycodes and the anland drag-move property. */
        private void openPropertyDialog() {
            if (controller == null) {
                return;
            }
            EditText textInput = new EditText(getContext());
            textInput.setHint("按键文字");
            textInput.setText(data.text);

            EditText codeInput = new EditText(getContext());
            codeInput.setHint("键码（按下事件，逗号分隔）");
            codeInput.setInputType(InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            StringBuilder sb = new StringBuilder();
            for (int c : data.pressEvent.outputKeycodes) {
                if (sb.length() > 0) {
                    sb.append(",");
                }
                sb.append(c);
            }
            codeInput.setText(sb.toString());

            Switch dragSwitch = new Switch(getContext());
            dragSwitch.setText("按住拖动移动鼠标");
            dragSwitch.setChecked(data.dragMoveMouse);

            Switch keepSwitch = new Switch(getContext());
            keepSwitch.setText("持续按住 (autoKeep)");
            keepSwitch.setChecked(data.pressEvent.autoKeep);

            LinearLayout layout = new LinearLayout(getContext());
            layout.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(12);
            layout.setPadding(pad, pad, pad, pad);
            layout.addView(textInput);
            layout.addView(codeInput);
            layout.addView(dragSwitch);
            layout.addView(keepSwitch);

            new AlertDialog.Builder(getContext())
                    .setTitle("编辑按键" + (data.text == null || data.text.isEmpty()
                            ? "" : " · " + data.text))
                    .setView(layout)
                    .setPositiveButton("保存", (d, w) -> savePropertyJson(
                            textInput.getText().toString(),
                            codeInput.getText().toString(),
                            dragSwitch.isChecked(),
                            keepSwitch.isChecked()))
                    .setNegativeButton("取消", null)
                    .show();
        }

        private void savePropertyJson(String text, String codes, boolean dragMove,
                                      boolean autoKeep) {
            JSONObject btn = controller.findControlJson(data.id);
            if (btn == null) {
                return;
            }
            try {
                btn.put("text", text);
                JSONObject ev = btn.optJSONObject("event");
                if (ev == null) {
                    ev = new JSONObject();
                    btn.put("event", ev);
                }
                ev.put("dragMoveMouse", dragMove);
                JSONObject press = ev.optJSONObject("pressEvent");
                if (press == null) {
                    press = new JSONObject();
                    ev.put("pressEvent", press);
                }
                press.put("autoKeep", autoKeep);
                JSONArray keycodes = new JSONArray();
                for (String s : codes.split(",")) {
                    String t = s.trim();
                    if (!t.isEmpty()) {
                        try {
                            keycodes.put(Integer.parseInt(t));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                press.put("outputKeycodes", keycodes);
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
                    }
                    return true;
            }
            return true;
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
