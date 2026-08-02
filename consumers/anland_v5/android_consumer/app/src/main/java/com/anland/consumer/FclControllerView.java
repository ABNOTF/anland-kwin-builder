package com.anland.consumer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

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
        void mouseScroll(int axis, float value);
        void text(String text);
        void toggleIme();
        void toggleVirtualKeyboard();
        void openSettings();
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float density;
    private final List<View> controls = new ArrayList<>();
    private final Map<String, Boolean> groupVisible = new HashMap<>();

    private FclController controller;
    private Bridge bridge;
    private float mouseSensitivity = 1f;

    public FclControllerView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setClipChildren(false);
    }

    public void setBridge(Bridge bridge) {
        this.bridge = bridge;
    }

    public void setController(FclController controller) {
        this.controller = controller;
        rebuild();
    }

    public boolean hasController() {
        return controller != null;
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
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility == VISIBLE && controller != null) {
            rebuild();
        }
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
                x = base.xPx(w, cw);
                y = base.yPx(h, ch);
            } else if (child instanceof FclDirectionView) {
                FclController.BaseInfo base = ((FclDirectionView) child).data.baseInfo;
                x = base.xPx(w, cw);
                y = base.yPx(h, ch);
            }
            child.layout(x, y, x + cw, y + ch);
        }
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
        private final FclController.Button data;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        private boolean pressed = false;
        private boolean moved = false;
        private boolean longPressFired = false;
        private float downX, downY;
        private long downTime;
        private int clickCount = 0;
        private long firstClickTime;

        private FclController.Event autoClickEvent;
        private boolean autoClickRunning = false;
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
                trigger(data.longPressEvent, true, false);
            }
        };

        FclButtonView(Context context, FclController.Button data) {
            super(context);
            this.data = data;
            setClickable(true);
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

            String text = data.text;
            if (text == null || text.isEmpty()) {
                return;
            }
            textPaint.setColor(pressed ? s.textColorPressed : s.textColor);
            textPaint.setTextSize((pressed ? s.textSizePressed : s.textSize) * density);
            StaticLayout layout = new StaticLayout(text, textPaint, getWidth(),
                    Layout.Alignment.ALIGN_CENTER, 1f, 0f, false);
            canvas.save();
            canvas.translate(0, (getHeight() - layout.getHeight()) / 2f);
            layout.draw(canvas);
            canvas.restore();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pressed = true;
                    invalidate();
                    downX = event.getX();
                    downY = event.getY();
                    downTime = System.currentTimeMillis();
                    moved = false;
                    longPressFired = false;
                    trigger(data.pressEvent, true, false);
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (Math.abs(dx) > 2 || Math.abs(dy) > 2) {
                        moved = true;
                        handler.removeCallbacks(longPressRunnable);
                    }
                    if (data.pointerFollow) {
                        bridge.mouseMove(dx * mouseSensitivity, dy * mouseSensitivity);
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
                        releaseEvent(data.longPressEvent, false);
                        longPressFired = false;
                    }
                    releaseEvent(data.pressEvent, false);

                    boolean tap = !moved && System.currentTimeMillis() - downTime <= 100;
                    if (tap) {
                        trigger(data.clickEvent, true, true);
                        clickCount++;
                        if (clickCount == 1) {
                            firstClickTime = System.currentTimeMillis();
                        } else if (clickCount == 2) {
                            if (System.currentTimeMillis() - firstClickTime < 400) {
                                trigger(data.doubleClickEvent, true, true);
                            } else {
                                clickCount = 1;
                                firstClickTime = System.currentTimeMillis();
                            }
                            clickCount = 0;
                        }
                    }
                    pressed = false;
                    invalidate();
                    return true;
            }
            return true;
        }

        private void trigger(FclController.Event ev, boolean enable, boolean clickType) {
            if (ev == null || !enable) {
                return;
            }
            if (ev.autoKeep) {
                if (ev.autoClick) {
                    startAutoClick(ev);
                } else {
                    keyDown(ev);
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

        private void releaseEvent(FclController.Event ev, boolean force) {
            if (ev == null) {
                return;
            }
            if (ev.autoClick) {
                stopAutoClick();
            }
            if (force || !ev.autoKeep) {
                keyUp(ev);
            }
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
                releaseEvent(data.longPressEvent, true);
                longPressFired = false;
            }
            releaseEvent(data.pressEvent, true);
            releaseEvent(data.clickEvent, true);
            releaseEvent(data.doubleClickEvent, true);
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
            bridge.mouseScroll(0, down ? 1 : 0);
        } else if (code == FCL_MOUSE_SCROLL_DOWN) {
            bridge.mouseScroll(0, down ? -1 : 0);
        } else {
            bridge.key(action, code);
        }
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
