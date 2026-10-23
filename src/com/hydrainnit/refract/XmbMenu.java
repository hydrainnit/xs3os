package com.hydrainnit.refract;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The XrossMediaBar itself: a horizontal rail of categories, a vertical column of items under the
 * selected one, sub-levels that slide in (Back slides them out), a detail panel on the right and
 * the clock box at the top right. One View that draws everything, so a D-pad press costs a few
 * Canvas calls and no layout pass. It only redraws while something is moving (and once a minute
 * for the clock); the animated background is a separate view underneath.
 */
final class XmbMenu extends View {

    /** One entry in a list. Everything is optional except the label. */
    static final class Node {
        String label, desc = "", meta = "", detail = "";
        int icon = XmbIcons.DOTS;
        Drawable art;
        int[] swatch;
        Bitmap image, logo;
        Runnable action;
        Supplier<String> value;
        Supplier<List<Node>> children;
        Supplier<List<Node>> options;
        Supplier<Boolean> checked;
        IntSupplier sget;
        IntConsumer sset;
        int smin, smax = 100, sstep = 5;
        String sunit = "";
        Runnable onFocus;
        Object tag;
        StaticLayout layout;
        int layoutW, layoutLines;
        String cutLabel, cutDesc, valText;
        Boolean checkedVal;
        long valAt;
        int valEpoch = -1;

        Node(String label) {
            this.label = label;
        }

        Node icon(int i) {
            icon = i;
            return this;
        }

        Node desc(String d) {
            desc = d == null ? "" : d;
            return this;
        }

        Node on(Runnable r) {
            action = r;
            return this;
        }

        Node value(Supplier<String> v) {
            value = v;
            return this;
        }

        Node sub(Supplier<List<Node>> c) {
            children = c;
            return this;
        }

        Node checked(Supplier<Boolean> c) {
            checked = c;
            return this;
        }

        Node slider(int min, int max, int step, String unit, IntSupplier get, IntConsumer set) {
            smin = min;
            smax = max;
            sstep = step;
            sunit = unit;
            sget = get;
            sset = set;
            return this;
        }
    }

    static final class Cat {
        final String id, name;
        final int icon;
        final Supplier<List<Node>> build;
        List<Node> nodes;
        int sel;

        Cat(String id, String name, int icon, Supplier<List<Node>> build) {
            this.id = id;
            this.name = name;
            this.icon = icon;
            this.build = build;
        }
    }

    private static final class Level {
        List<Node> nodes;
        int sel;
        float pos;
        String title;
    }

    interface Host {
        void onCategory(Cat c, int dir);

        void onItem(Node n, int dirY);

        void onActivate(Node n);

        void onBack();

        void onTick(int kind);

        void onOptions(Node n);
    }

    static final int TICK_MOVE = 0, TICK_OK = 1, TICK_BACK = 2, TICK_BAD = 3, TICK_MOVE_H = 4;

    private final float u;
    private final Host host;
    private final List<Cat> cats = new ArrayList<>();
    private int catSel;
    private float catPos;
    private final List<Level> stack = new ArrayList<>();
    private Level outgoing;
    private int slideDir;
    private float slideT = 1f;

    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint img = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final android.graphics.Path clip = new android.graphics.Path();
    private final Rect src = new Rect();
    private Typeface face;
    private int inkColor = 0xFFFFFFFF;
    private boolean light;
    private long lastFrame;
    private boolean animating;
    private String clock = "", date = "";
    private long clockMinute = -1;
    private String statusRight = "";
    private float dim = 1f;
    private int epoch;

    /** Row values and ticks come from system calls; they are re-read at most once a second, or after any key. */
    private void sync(Node n, long now) {
        if (n.valEpoch == epoch && now - n.valAt < 1000) return;
        n.valEpoch = epoch;
        n.valAt = now;
        n.valText = n.sget != null ? null : n.value != null ? n.value.get() : null;
        n.checkedVal = n.checked != null ? n.checked.get() : null;
    }
    private boolean setupMode;

    XmbMenu(Context c, Host host) {
        super(c);
        this.host = host;
        XmbIcons.init(c);
        u = Ui.unit(c);
        setFocusable(false);
        setWillNotDraw(false);
        applyTheme();
    }

    void applyTheme() {
        Context c = getContext();
        light = Theme.isLight(c);
        inkColor = Xmb.ink(c);
        face = Xmb.font(c);
        text.setTypeface(face);
        invalidate();
    }

    void setStatus(String s) {
        statusRight = s == null ? "" : s;
        invalidate();
    }

    /** 0..1 fade of the whole menu (used under overlays). */
    void setDim(float d) {
        dim = d;
        invalidate();
    }

    // ------------------------------------------------------------------ model

    void setCategories(List<Cat> list, int select) {
        cats.clear();
        cats.addAll(list);
        catSel = Math.max(0, Math.min(select, cats.size() - 1));
        catPos = catSel;
        stack.clear();
        outgoing = null;
        slideT = 1f;
        if (!cats.isEmpty()) loadRoot();
        invalidate();
    }

    int category() {
        return catSel;
    }

    Cat currentCat() {
        return cats.isEmpty() ? null : cats.get(catSel);
    }

    int depth() {
        return stack.size();
    }

    Node selected() {
        if (stack.isEmpty()) return null;
        Level l = stack.get(stack.size() - 1);
        return l.nodes.isEmpty() ? null : l.nodes.get(Math.max(0, Math.min(l.sel, l.nodes.size() - 1)));
    }

    private void loadRoot() {
        Cat c = cats.get(catSel);
        if (c.nodes == null) c.nodes = c.build.get();
        Level l = new Level();
        l.nodes = c.nodes;
        l.sel = Math.max(0, Math.min(c.sel, Math.max(0, l.nodes.size() - 1)));
        l.pos = l.sel;
        stack.clear();
        stack.add(l);
        notifySelection(0);
    }

    /** Rebuilds the visible list in place (after a setting or data change), keeping the selection. */
    void refresh() {
        if (stack.isEmpty()) return;
        Cat c = cats.get(catSel);
        Level top = stack.get(stack.size() - 1);
        for (Cat other : cats) if (other != c) other.nodes = null;
        epoch++;
        if (stack.size() == 1) {
            c.nodes = c.build.get();
            top.nodes = c.nodes;
        } else if (top.title != null && stack.size() >= 2) {
            Level parent = stack.get(stack.size() - 2);
            Node owner = parent.nodes.isEmpty() ? null : parent.nodes.get(Math.min(parent.sel, parent.nodes.size() - 1));
            if (owner != null && owner.children != null) top.nodes = owner.children.get();
        }
        top.sel = Math.max(0, Math.min(top.sel, Math.max(0, top.nodes.size() - 1)));
        invalidate();
    }

    /** Select the item whose label matches, in the current list. */
    void select(String label) {
        Level l = stack.get(stack.size() - 1);
        for (int i = 0; i < l.nodes.size(); i++) {
            if (label.equals(l.nodes.get(i).label)) {
                l.sel = i;
                l.pos = i;
                invalidate();
                return;
            }
        }
    }

    private void notifySelection(int dirY) {
        Node n = selected();
        if (n != null) {
            if (n.onFocus != null) n.onFocus.run();
            host.onItem(n, dirY);
        }
    }

    // ------------------------------------------------------------------ input

    boolean onKey(int code, int repeat) {
        epoch++;
        if (cats.isEmpty() || stack.isEmpty()) return false;
        Level l = stack.get(stack.size() - 1);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                int dir = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                Node n = selected();
                if (n != null && n.sget != null) {
                    int v = n.sget.getAsInt(), nv = Math.max(n.smin, Math.min(n.smax, v + dir * n.sstep));
                    if (nv != v) {
                        n.sset.accept(nv);
                        host.onTick(TICK_MOVE);
                    } else {
                        host.onTick(TICK_BAD);
                    }
                    invalidate();
                    return true;
                }
                if (stack.size() > 1) {
                    if (dir < 0) back();
                    return true;
                }
                moveCategory(dir);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                int dir = code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1;
                int ns = l.sel + dir;
                if (ns < 0 || ns >= l.nodes.size()) {
                    if (repeat == 0) host.onTick(TICK_BAD);
                    return true;
                }
                l.sel = ns;
                if (stack.size() == 1) cats.get(catSel).sel = ns;
                host.onTick(TICK_MOVE);
                notifySelection(dir);
                kick();
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (repeat == 0) activate();
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_BUTTON_Y: {
                Node n = selected();
                if (n != null && n.options != null) {
                    host.onTick(TICK_OK);
                    host.onOptions(n);
                }
                return true;
            }
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BUTTON_B:
                if (repeat == 0) {
                    if (stack.size() > 1) {
                        host.onTick(TICK_BACK);
                        back();
                    } else {
                        host.onBack();
                    }
                }
                return true;
            default:
                return false;
        }
    }

    /** Builds the other categories' lists one per frame while idle, so switching later costs nothing. */
    void prewarm() {
        for (final Cat c : cats) {
            if (c.nodes == null && c != cats.get(catSel)) {
                postDelayed(() -> {
                    if (c.nodes == null) c.nodes = c.build.get();
                    prewarm();
                }, 120);
                return;
            }
        }
    }

    /** Moves to the next/previous category (used by Continue buttons in setup). */
    void goCategory(int dir) {
        moveCategory(dir);
    }

    private void moveCategory(int dir) {
        int n = catSel + dir;
        if (n < 0 || n >= cats.size()) {
            host.onTick(TICK_BAD);
            return;
        }
        catSel = n;
        host.onTick(TICK_MOVE_H);
        loadRoot();
        host.onCategory(cats.get(catSel), dir);
        kick();
    }

    private void activate() {
        Node n = selected();
        if (n == null) return;
        if (n.sget != null) {
            host.onTick(TICK_BAD);
            return;
        }
        if (n.children != null) {
            List<Node> kids = n.children.get();
            host.onTick(TICK_OK);
            if (kids == null || kids.isEmpty()) {
                host.onTick(TICK_BAD);
                return;
            }
            Level l = new Level();
            l.nodes = kids;
            l.title = n.label;
            for (int i = 0; i < kids.size(); i++) {
                Supplier<Boolean> ck = kids.get(i).checked;
                if (ck != null && Boolean.TRUE.equals(ck.get())) {
                    l.sel = i;
                    break;
                }
            }
            l.pos = l.sel;
            outgoing = stack.get(stack.size() - 1);
            slideDir = 1;
            slideT = 0f;
            stack.add(l);
            notifySelection(0);
            kick();
            return;
        }
        if (n.action != null) {
            host.onTick(TICK_OK);
            host.onActivate(n);
            n.action.run();
            invalidate();
        } else {
            host.onTick(TICK_BAD);
        }
    }

    /** Pushes an ad-hoc level (e.g. a long-press options list) with the same slide as a folder. */
    void push(String title, List<Node> kids) {
        if (kids == null || kids.isEmpty() || stack.isEmpty()) return;
        Level l = new Level();
        l.nodes = kids;
        l.title = title;
        outgoing = stack.get(stack.size() - 1);
        slideDir = 1;
        slideT = 0f;
        stack.add(l);
        notifySelection(0);
        kick();
    }

    /** Pops one level (no-op at the root). */
    boolean back() {
        if (stack.size() <= 1) return false;
        outgoing = stack.remove(stack.size() - 1);
        slideDir = -1;
        slideT = 0f;
        notifySelection(0);
        kick();
        return true;
    }

    // ------------------------------------------------------------------ animation

    private void kick() {
        if (!animating) {
            animating = true;
            lastFrame = SystemClock.uptimeMillis();
            Choreographer.getInstance().postFrameCallback(frame);
        }
        invalidate();
    }

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long nanos) {
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.05f, (now - lastFrame) / 1000f);
            lastFrame = now;
            boolean more = false;
            float lag = Math.max(Math.abs(catSel - catPos), 0.5f);
            for (Level lv : stack) lag = Math.max(lag, Math.abs(lv.sel - lv.pos));
            float k = 1f - (float) Math.exp(-dt * (42f + 26f * Math.min(lag, 4f)));
            float dc = catSel - catPos;
            if (Math.abs(dc) > 0.005f) {
                catPos += dc * k;
                more = true;
            } else {
                catPos = catSel;
            }
            for (Level l : stack) {
                float d = l.sel - l.pos;
                if (Math.abs(d) > 0.005f) {
                    l.pos += d * k;
                    more = true;
                } else {
                    l.pos = l.sel;
                }
            }
            if (slideT < 1f) {
                slideT = Math.min(1f, slideT + dt / 0.11f);
                more = true;
                if (slideT >= 1f) outgoing = null;
            }
            invalidate();
            if (more && isAttachedToWindow()) Choreographer.getInstance().postFrameCallback(this);
            else animating = false;
        }
    };

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Choreographer.getInstance().removeFrameCallback(frame);
        animating = false;
    }

    // ------------------------------------------------------------------ drawing

    private static float smooth(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3 - 2 * t);
    }

    private int a(int color, float alpha) {
        return (color & 0x00FFFFFF) | (Math.round(255 * Math.max(0f, Math.min(1f, alpha)) * dim) << 24);
    }

    /** Blur shadows are costly on TV GPUs; contrast comes from the waves staying quiet instead. */
    private void shadowOn(Paint p) {
    }

    @Override
    protected void onDraw(Canvas c) {
        if (cats.isEmpty() || dim <= 0.01f) return;
        int w = getWidth(), h = getHeight();
        drawStatus(c, w);
        drawRail(c, w, h);
        float ease = smooth(slideT);
        if (outgoing != null) {
            float off = slideDir > 0 ? -ease * 46 * u : ease * 46 * u;
            drawList(c, outgoing, off, 1f - ease, w, h, false);
            float in = slideDir > 0 ? (1f - ease) * 46 * u : -(1f - ease) * 46 * u;
            drawList(c, stack.get(stack.size() - 1), in, ease, w, h, true);
        } else {
            drawList(c, stack.get(stack.size() - 1), 0, 1f, w, h, true);
        }
        drawDetail(c, w, h);
    }


    private float railY(int h) {
        return h * 0.255f;
    }

    private float catX(int w) {
        return w * 0.215f;
    }

    private void drawStatus(Canvas c, int w) {
        long minute = System.currentTimeMillis() / 60000L;
        if (minute != clockMinute) {
            clockMinute = minute;
            Date d = new Date();
            clock = DateFormat.getTimeInstance(DateFormat.SHORT).format(d);
            date = new java.text.SimpleDateFormat(android.text.format.DateFormat.is24HourFormat(getContext())
                    ? "d/M HH:mm" : "d/M h:mm", java.util.Locale.getDefault()).format(d);
            postDelayed(this::invalidate, 61000 - System.currentTimeMillis() % 60000L);
        }
        // Proportions of the PS3 status bar: 304 x 31 on a 1080-wide frame, 28 px from the right, 42 from the top.
        float k = w / 1080f, bw = 304 * k, bh = 31 * k, x = w - bw - 28 * k, y = 42 * k;
        rect.set(x, y, x + bw, y + bh);
        box.setStyle(Paint.Style.FILL);
        box.setColor(a(0xFFFFFFFF, light ? 0.30f : 0.09f));
        c.drawRect(rect, box);
        box.setStyle(Paint.Style.STROKE);
        box.setStrokeWidth(Math.max(1f, 1.2f * k));
        box.setColor(a(inkColor, 0.45f));
        c.drawRect(rect, box);
        float cy = y + bh / 2f;
        ink.setStyle(Paint.Style.FILL);
        ink.setColor(a(inkColor, 0.85f));
        XmbIcons.draw(c, XmbIcons.WIFI, x + 18 * k, cy, 14 * k, ink);
        XmbIcons.draw(c, XmbIcons.CLOCK_PLAIN, x + bw - 17 * k, cy, 17 * k, ink);
        text.setTextSize(18 * k);
        text.setColor(a(inkColor, 0.95f));
        text.setShadowLayer(0, 0, 0, 0);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText(date, x + bw - 34 * k, cy + 6.2f * k, text);
        text.setTextAlign(Paint.Align.LEFT);
        if (!statusRight.isEmpty()) {
            text.setTextSize(12 * u);
            text.setColor(a(inkColor, 0.7f));
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText(statusRight, x + bw, y + bh + 18 * u, text);
            text.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void drawRail(Canvas c, int w, int h) {
        float ry = railY(h), cx0 = catX(w), gap = 98 * u;
        for (int i = 0; i < cats.size(); i++) {
            float d = i - catPos;
            float x = cx0 + d * gap + (d > 0 ? Math.min(1f, d) * 26 * u : (d < 0 ? Math.max(-1f, d) * 8 * u : 0));
            float sel = 1f - Math.min(1f, Math.abs(d));
            float size = (44 + 26 * sel) * u;
            float alpha = (0.42f + 0.58f * sel) * smooth(1f - Math.max(0f, (-d - 0.2f)) * 0.9f);
            if (stack.size() > 1) alpha *= 0.55f + 0.45f * sel;
            if (x < -size || x > w + size || alpha <= 0.01f) continue;
            ink.setColor(a(inkColor, alpha));
            ink.setStyle(Paint.Style.FILL);
            ink.setStrokeWidth(Math.max(1.5f, 2.2f * u));
            shadowOn(ink);
            XmbIcons.draw(c, cats.get(i).icon, x, ry - 6 * u * sel, size, ink);
            ink.clearShadowLayer();
            if (sel > 0.05f) {
                text.setTextSize(15 * u);
                text.setColor(a(inkColor, sel));
                shadowOn(text);
                text.setTextAlign(Paint.Align.CENTER);
                c.drawText(cats.get(i).name, x, ry + 42 * u, text);
                text.setTextAlign(Paint.Align.LEFT);
                text.clearShadowLayer();
            }
        }
    }

    private void drawList(Canvas c, Level l, float xoff, float alpha, int w, int h, boolean active) {
        if (alpha <= 0.01f) return;
        float ry = railY(h), x0 = catX(w) + xoff, selY = ry + h * 0.20f;
        float rowH = 50 * u, gapBelow = 34 * u, gapAbove = 8 * u;
        if (stack.size() > 1 && l.title != null) {
            text.setTextSize(12 * u);
            text.setColor(a(inkColor, 0.65f * alpha));
            text.clearShadowLayer();
            c.drawText(l.title.toUpperCase(java.util.Locale.ROOT), x0 - 22 * u, ry + 80 * u, text);
        }
        long now = SystemClock.uptimeMillis();
        int n = l.nodes.size();
        int first = Math.max(0, (int) Math.floor(l.pos) - 3), last = Math.min(n - 1, (int) Math.ceil(l.pos) + 6);
        for (int i = first; i <= last; i++) {
            float d = i - l.pos;
            float y = selY + d * rowH + (d > 0 ? Math.min(1f, d) * gapBelow : (d < 0 ? Math.max(-1f, d) * gapAbove : 0));
            float fadeTop = smooth((y - (ry + (l.title != null ? 90 : 62) * u)) / (40 * u));
            float fadeBot = smooth(((h - 24 * u) - y) / (50 * u));
            float sel = 1f - Math.min(1f, Math.abs(d));
            float al = alpha * fadeTop * fadeBot * (0.55f + 0.45f * sel);
            if (al <= 0.01f) continue;
            Node nd = l.nodes.get(i);
            float isz = (34 + 20 * sel) * u;
            drawItemIcon(c, nd, x0, y, isz, al, sel);
            float tx = x0 + 52 * u;
            text.setTextSize((17 + 4 * sel) * u);
            text.setColor(a(inkColor, al));
            shadowOn(text);
            float maxW = 290 * u;
            if (nd.cutLabel == null) {
                text.setTextSize(21 * u);
                nd.cutLabel = TextUtils.ellipsize(nd.label, text, maxW, TextUtils.TruncateAt.END).toString();
                text.setTextSize((17 + 4 * sel) * u);
            }
            String lab = nd.cutLabel;
            boolean sl = nd.sget != null;
            c.drawText(lab, tx, y + (sl && sel > 0.5f ? -2 * u : 6 * u), text);
            text.clearShadowLayer();
            if (sel > 0.1f && active) {
                float sy = y + 6 * u + 20 * u;
                if (sl) {
                    drawSlider(c, nd, tx, y + 14 * u, Math.min(maxW, 300 * u), al);
                } else if (!nd.desc.isEmpty()) {
                    text.setTextSize(13 * u);
                    text.setColor(a(inkColor, 0.72f * sel * al));
                    if (nd.cutDesc == null) nd.cutDesc = TextUtils.ellipsize(nd.desc, text, 560 * u, TextUtils.TruncateAt.END).toString();
                    c.drawText(nd.cutDesc, tx, sy, text);
                }
            }
            sync(nd, now);
            String val = nd.valText;
            Boolean ck = nd.checkedVal;
            if (ck != null && ck && nd.value == null) val = "✓";
            if (val != null && !val.isEmpty()) {
                text.setTextSize(14 * u);
                text.setColor(a(inkColor, 0.78f * al));
                text.setTextAlign(Paint.Align.RIGHT);
                float vx = tx + 440 * u;
                c.drawText(val, vx, y + 5 * u, text);
                text.setTextAlign(Paint.Align.LEFT);
            }
            if (nd.children != null && sel > 0.1f && active) {
                ink.setColor(a(inkColor, 0.6f * al));
                ink.setStyle(Paint.Style.STROKE);
                ink.setStrokeWidth(Math.max(1.5f, 1.8f * u));
                ink.setStrokeCap(Paint.Cap.ROUND);
                float ax = tx + 458 * u;
                c.drawLine(ax, y - 5 * u, ax + 5 * u, y, ink);
                c.drawLine(ax + 5 * u, y, ax, y + 5 * u, ink);
            }
        }
    }

    private final android.graphics.Path swClip = new android.graphics.Path();

    private void drawItemIcon(Canvas c, Node nd, float x, float y, float size, float al, float sel) {
        if (nd.swatch != null) {
            float s = size * 0.8f, h = s / 2f;
            rect.set(x - h, y - h, x + h, y + h);
            swClip.reset();
            swClip.addRoundRect(rect, 7 * u, 7 * u, android.graphics.Path.Direction.CW);
            c.save();
            c.clipPath(swClip);
            box.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 3; i++) {
                box.setColor((nd.swatch[i] & 0x00FFFFFF) | (Math.round(255 * Math.min(1f, al)) << 24));
                c.drawRect(x - h, y - h + s * i / 3f, x + h, y - h + s * (i + 1) / 3f + 1, box);
            }
            c.restore();
            box.setStyle(Paint.Style.STROKE);
            box.setStrokeWidth(Math.max(1f, 1.4f * u));
            box.setColor(a(0xFFFFFFFF, 0.55f * al));
            c.drawRoundRect(rect, 7 * u, 7 * u, box);
            return;
        }
        if (nd.art != null) {
            int s = Math.round(size);
            nd.art.setBounds(Math.round(x - s / 2f), Math.round(y - s / 2f), Math.round(x + s / 2f), Math.round(y + s / 2f));
            nd.art.setAlpha(Math.round(255 * al * dim));
            nd.art.draw(c);
            return;
        }
        if (nd.image != null && nd.logo == null && nd.icon == XmbIcons.PLAY) {
            drawBitmapFit(c, nd.image, x - size / 2f, y - size / 2f, size, size, al, 4 * u);
            return;
        }
        ink.setColor(a(inkColor, al));
        ink.setStyle(Paint.Style.FILL);
        ink.setStrokeWidth(Math.max(1.4f, 1.6f * u));
        shadowOn(ink);
        XmbIcons.draw(c, nd.icon, x, y, size * 0.8f, ink);
        ink.clearShadowLayer();
    }

    private void drawSlider(Canvas c, Node nd, float x, float y, float width, float al) {
        int v = nd.sget.getAsInt();
        float f = (v - nd.smin) / (float) Math.max(1, nd.smax - nd.smin);
        float bh = 4 * u;
        rect.set(x, y, x + width, y + bh);
        box.setStyle(Paint.Style.FILL);
        box.setColor(a(inkColor, 0.25f * al));
        c.drawRoundRect(rect, bh / 2, bh / 2, box);
        rect.set(x, y, x + width * f, y + bh);
        box.setColor(a(inkColor, 0.95f * al));
        c.drawRoundRect(rect, bh / 2, bh / 2, box);
        c.drawCircle(x + width * f, y + bh / 2, 6 * u, box);
        text.setTextSize(13 * u);
        text.setColor(a(inkColor, 0.85f * al));
        c.drawText(v + nd.sunit, x + width + 14 * u, y + bh + 3 * u, text);
    }

    private void drawBitmapFit(Canvas c, Bitmap b, float x, float y, float w, float h, float al, float radius) {
        float s = Math.max(w / b.getWidth(), h / b.getHeight());
        int sw = Math.round(w / s), sh = Math.round(h / s);
        int sx = (b.getWidth() - sw) / 2, sy = (b.getHeight() - sh) / 2;
        src.set(sx, sy, sx + sw, sy + sh);
        rect.set(x, y, x + w, y + h);
        img.setAlpha(Math.round(255 * al * dim));
        c.drawBitmap(b, src, rect, img);
    }

    private void drawDetail(Canvas c, int w, int h) {
        Node n = selected();
        if (n == null) return;
        boolean hasText = !n.detail.isEmpty() || !n.meta.isEmpty();
        if (n.image == null && n.logo == null && !hasText) return;
        if (slideT < 1f) return;
        float pw = w * 0.31f, px = w - pw - 44 * u, py = railY(h) + 70 * u;
        float imgH = pw * (n.logo != null || n.image != null && n.image.getWidth() >= n.image.getHeight() ? 0.5625f : 1.0f);
        if (n.image != null) {
            // Slightly smaller than the panel so the title, meta and a few lines of description fit above the bottom margin.
            float shrink = hasText ? 0.78f : 1f;
            float ih = (n.image.getWidth() >= n.image.getHeight() ? pw * 0.5625f : pw * 0.9f) * shrink;
            float iw = n.image.getWidth() >= n.image.getHeight() ? pw * shrink : ih * n.image.getWidth() / (float) n.image.getHeight();
            drawBitmapFit(c, n.image, px, py, iw, ih, 1f, 4 * u);
            imgH = ih;
        } else {
            imgH = 0;
        }
        float y = py + imgH + (imgH > 0 ? 14 * u : 0);
        if (n.logo != null) {
            float lh = 44 * u, lw = Math.min(pw, lh * n.logo.getWidth() / (float) n.logo.getHeight());
            rect.set(px, y, px + lw, y + lh);
            img.setAlpha(Math.round(255 * dim));
            c.drawBitmap(n.logo, null, rect, img);
            y += lh + 10 * u;
        } else if (n.tag instanceof String && !((String) n.tag).isEmpty()) {
            text.setTextSize(19 * u);
            text.setColor(a(inkColor, 1f));
            shadowOn(text);
            c.drawText(TextUtils.ellipsize((String) n.tag, text, pw, TextUtils.TruncateAt.END).toString(), px, y + 16 * u, text);
            text.clearShadowLayer();
            y += 28 * u;
        }
        if (!n.meta.isEmpty()) {
            text.setTextSize(13 * u);
            text.setColor(a(inkColor, 0.85f));
            c.drawText(TextUtils.ellipsize(n.meta, text, pw, TextUtils.TruncateAt.END).toString(), px, y + 12 * u, text);
            y += 24 * u;
        }
        if (!n.detail.isEmpty()) {
            int lw = Math.round(pw);
            // Keep the description inside the readable area: as many lines as fit above the bottom margin, at most 7.
            float lineH = 13 * u * 1.21f + 2 * u;
            int lines = Math.max(1, Math.min(7, (int) ((h - 64 * u - y) / lineH)));
            if (n.layout == null || n.layoutW != lw || n.layoutLines != lines) {
                TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                tp.setTypeface(face);
                tp.setTextSize(13 * u);
                tp.setColor(0xFFFFFFFF);
                n.layout = StaticLayout.Builder.obtain(n.detail, 0, n.detail.length(), tp, lw)
                        .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(2 * u, 1f).build();
                n.layoutW = lw;
                n.layoutLines = lines;
            }
            text.setTextSize(13 * u);
            text.setColor(a(inkColor, 0.82f));
            c.save();
            c.translate(px, y);
            Layout lay = n.layout;
            lay.getPaint().setColor(a(inkColor, 0.82f));
            lay.draw(c);
            c.restore();
        }
    }
}
