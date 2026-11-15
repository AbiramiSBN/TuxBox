package dev.tuxbox

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Draws the remote framebuffer scaled to fit and translates touch, mouse and
 * keyboard input into VNC events.
 *
 * Touch: tap = left click, long-press = right click, drag = left-drag,
 * two-finger drag = scroll wheel.
 */
@SuppressLint("ViewConstructor")
class VncView(context: Context, var client: VncClient) : View(context) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = Rect()
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var longPressed = false
    private var multi = false
    private var touchScrollY = 0f
    private var mouseButtons = 0
    private val heldModifiers = mutableSetOf<Int>()

    private val longPress = Runnable {
        longPressed = true
        performHapticFeedback(HAPTIC_FEEDBACK_ENABLED)
        val (x, y) = toRemote(downX, downY)
        click(x, y, BTN_RIGHT)
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        synchronized(client.lock) {
            val bmp = client.bitmap ?: return
            if (client.width == 0) return
            scale = minOf(width.toFloat() / client.width, height.toFloat() / client.height)
            val w = client.width * scale
            val h = client.height * scale
            offX = (width - w) / 2
            offY = (height - h) / 2
            dst.set(offX.toInt(), offY.toInt(), (offX + w).toInt(), (offY + h).toInt())
            canvas.drawBitmap(bmp, null, dst, paint)
        }
    }

    /** Last pointer position in remote coordinates (used by the controller). */
    private var curX = 0f
    private var curY = 0f

    private fun ptr(x: Int, y: Int, buttons: Int) {
        curX = x.toFloat(); curY = y.toFloat()
        client.pointer(x, y, buttons)
    }

    private fun toRemote(x: Float, y: Float): Pair<Int, Int> =
        ((x - offX) / scale).toInt() to ((y - offY) / scale).toInt()

    private fun click(x: Int, y: Int, button: Int) {
        ptr(x, y, 0)
        ptr(x, y, button)
        ptr(x, y, 0)
    }

    // ----------------------------------------------------------------- touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_MOUSE)) return onMouse(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                downX = e.x; downY = e.y
                dragging = false; longPressed = false; multi = false
                val (x, y) = toRemote(e.x, e.y)
                ptr(x, y, 0)
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPress)
                if (dragging) { val (x, y) = toRemote(e.x, e.y); ptr(x, y, 0); dragging = false }
                multi = true
                touchScrollY = avgY(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (multi) {
                    val y = avgY(e)
                    val step = 40 * resources.displayMetrics.density
                    val (rx, ry) = toRemote(downX, downY)
                    while (abs(y - touchScrollY) >= step) {
                        val up = y > touchScrollY // fingers moving down = scroll up (natural)
                        click(rx, ry, if (up) BTN_WHEEL_UP else BTN_WHEEL_DOWN)
                        touchScrollY += if (up) step else -step
                    }
                } else if (!longPressed) {
                    if (!dragging && hypot(e.x - downX, e.y - downY) > slop) {
                        removeCallbacks(longPress)
                        dragging = true
                        val (x, y) = toRemote(downX, downY)
                        ptr(x, y, BTN_LEFT)
                    }
                    if (dragging) { val (x, y) = toRemote(e.x, e.y); ptr(x, y, BTN_LEFT) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                val (x, y) = toRemote(e.x, e.y)
                when {
                    dragging -> ptr(x, y, 0)
                    !longPressed && !multi && e.actionMasked == MotionEvent.ACTION_UP -> click(x, y, BTN_LEFT)
                }
                dragging = false
            }
        }
        return true
    }

    private fun avgY(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getY(i)
        return s / e.pointerCount
    }

    // -------------------------------------------------- hardware mouse/trackpad

    private fun onMouse(e: MotionEvent): Boolean {
        val (x, y) = toRemote(e.x, e.y)
        var b = 0
        if (e.buttonState and MotionEvent.BUTTON_PRIMARY != 0) b = b or BTN_LEFT
        if (e.buttonState and MotionEvent.BUTTON_TERTIARY != 0) b = b or BTN_MIDDLE
        if (e.buttonState and MotionEvent.BUTTON_SECONDARY != 0) b = b or BTN_RIGHT
        mouseButtons = b
        ptr(x, y, b)
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_JOYSTICK) && e.actionMasked == MotionEvent.ACTION_MOVE) return onJoystick(e)
        if (!e.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) return super.onGenericMotionEvent(e)
        val (x, y) = toRemote(e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> ptr(x, y, mouseButtons)
            MotionEvent.ACTION_SCROLL -> {
                val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (v != 0f) {
                    val btn = if (v > 0) BTN_WHEEL_UP else BTN_WHEEL_DOWN
                    ptr(x, y, mouseButtons or btn); ptr(x, y, mouseButtons)
                }
            }
            else -> return super.onGenericMotionEvent(e)
        }
        return true
    }

    // -------------------------------------------------------------- keyboard

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(this, 0)
    }

    fun toggleKeyboard() {
        requestFocus()
        @Suppress("DEPRECATION")
        context.getSystemService(InputMethodManager::class.java)
            .toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
    }

    /** Toggles a sticky modifier from the on-screen key bar. Returns true if now held. */
    fun toggleModifier(keysym: Int): Boolean {
        val held = keysym !in heldModifiers
        if (held) heldModifiers += keysym else heldModifiers -= keysym
        client.key(keysym, held)
        return held
    }

    private fun releaseModifiers() {
        heldModifiers.forEach { client.key(it, false) }
        heldModifiers.clear()
        onModifiersReleased?.invoke()
    }

    var onModifiersReleased: (() -> Unit)? = null

    fun tapKey(keysym: Int) {
        client.key(keysym, true); client.key(keysym, false)
        if (heldModifiers.isNotEmpty()) releaseModifiers()
    }

    fun typeText(text: CharSequence) {
        for (ch in text.toString()) {
            if (ch == '\n') tapKey(Keysyms.RETURN) else tapKey(Keysyms.forChar(ch.code))
        }
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            private var composing = ""

            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                repeat(composing.length) { tapKey(Keysyms.BACKSPACE) }
                composing = ""
                typeText(text)
                return true
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                val t = text.toString()
                val common = composing.commonPrefixWith(t).length
                repeat(composing.length - common) { tapKey(Keysyms.BACKSPACE) }
                typeText(t.substring(common))
                composing = t
                return true
            }

            override fun finishComposingText(): Boolean { composing = ""; return true }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) { tapKey(Keysyms.BACKSPACE) }
                repeat(afterLength) { tapKey(Keysyms.DELETE) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event)
                else if (event.action == KeyEvent.ACTION_UP) onKeyUp(event.keyCode, event)
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (KeyEvent.isGamepadButton(keyCode)) { if (event.repeatCount == 0) padButton(keyCode, true); return true }
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        val sym = Keysyms.forKeyEvent(event) ?: return super.onKeyDown(keyCode, event)
        client.key(sym, true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (KeyEvent.isGamepadButton(keyCode)) { padButton(keyCode, false); return true }
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyUp(keyCode, event)
        val sym = Keysyms.forKeyEvent(event) ?: return super.onKeyUp(keyCode, event)
        client.key(sym, false)
        if (heldModifiers.isNotEmpty() && sym !in Keysyms.MODIFIERS) releaseModifiers()
        return true
    }

    // ------------------------------------------------------------- controller
    //  Left stick: mouse   Right stick: scroll   D-pad: arrow keys
    //  A: left click (hold to drag)  B: right click  L3: middle click
    //  X: Enter  Y: Backspace  Select: Esc  Start: on-screen keyboard
    //  L1: Super (hold)  R1: Alt (hold)  L2: Ctrl (hold)  R2: Tab  R3: show/hide key bar

    var onToggleBar: (() -> Unit)? = null
    private var padButtons = 0
    private var lx = 0f; private var ly = 0f; private var rx = 0f; private var ry = 0f
    private var scrollAccX = 0f; private var scrollAccY = 0f
    private var hatX = 0; private var hatY = 0
    private var l2 = false; private var r2 = false
    private var ticking = false

    private fun dead(v: Float) = if (abs(v) < 0.15f) 0f else v

    private fun onJoystick(e: MotionEvent): Boolean {
        lx = dead(e.getAxisValue(MotionEvent.AXIS_X)); ly = dead(e.getAxisValue(MotionEvent.AXIS_Y))
        rx = dead(e.getAxisValue(MotionEvent.AXIS_Z)); ry = dead(e.getAxisValue(MotionEvent.AXIS_RZ))
        // D-pad reported as a hat on many controllers
        val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X).let { if (it > 0.5f) 1 else if (it < -0.5f) -1 else 0 }
        val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y).let { if (it > 0.5f) 1 else if (it < -0.5f) -1 else 0 }
        if (hx != hatX) {
            if (hatX != 0) client.key(if (hatX < 0) Keysyms.LEFT else Keysyms.RIGHT, false)
            if (hx != 0) client.key(if (hx < 0) Keysyms.LEFT else Keysyms.RIGHT, true)
            hatX = hx
        }
        if (hy != hatY) {
            if (hatY != 0) client.key(if (hatY < 0) Keysyms.UP else Keysyms.DOWN, false)
            if (hy != 0) client.key(if (hy < 0) Keysyms.UP else Keysyms.DOWN, true)
            hatY = hy
        }
        // Analog triggers on controllers that don't also send L2/R2 key events
        val lt = maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)) > 0.5f
        val rt = maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS)) > 0.5f
        if (lt != l2) padButton(KeyEvent.KEYCODE_BUTTON_L2, lt)
        if (rt != r2) padButton(KeyEvent.KEYCODE_BUTTON_R2, rt)
        if (!ticking && (lx != 0f || ly != 0f || rx != 0f || ry != 0f)) { ticking = true; post(padTick) }
        return true
    }

    private val padTick = object : Runnable {
        override fun run() {
            if (lx == 0f && ly == 0f && rx == 0f && ry == 0f) { ticking = false; return }
            val w = maxOf(client.width, 1); val h = maxOf(client.height, 1)
            if (lx != 0f || ly != 0f) {
                val speed = w / 70f // full deflection crosses the screen in ~1.1 s
                curX = (curX + lx * abs(lx) * speed).coerceIn(0f, w - 1f)
                curY = (curY + ly * abs(ly) * speed).coerceIn(0f, h - 1f)
                ptr(curX.toInt(), curY.toInt(), padButtons)
            }
            scrollAccY += ry * 0.3f; scrollAccX += rx * 0.3f
            while (abs(scrollAccY) >= 1f) {
                val b = if (scrollAccY < 0) BTN_WHEEL_UP else BTN_WHEEL_DOWN
                ptr(curX.toInt(), curY.toInt(), padButtons or b); ptr(curX.toInt(), curY.toInt(), padButtons)
                scrollAccY -= if (scrollAccY < 0) -1f else 1f
            }
            while (abs(scrollAccX) >= 1f) {
                val b = if (scrollAccX < 0) BTN_WHEEL_LEFT else BTN_WHEEL_RIGHT
                ptr(curX.toInt(), curY.toInt(), padButtons or b); ptr(curX.toInt(), curY.toInt(), padButtons)
                scrollAccX -= if (scrollAccX < 0) -1f else 1f
            }
            postDelayed(this, 16)
        }
    }

    private fun mouseButton(mask: Int, down: Boolean) {
        padButtons = if (down) padButtons or mask else padButtons and mask.inv()
        ptr(curX.toInt(), curY.toInt(), padButtons)
    }

    private fun padButton(code: Int, down: Boolean) {
        when (code) {
            KeyEvent.KEYCODE_BUTTON_A -> mouseButton(BTN_LEFT, down)
            KeyEvent.KEYCODE_BUTTON_B -> mouseButton(BTN_RIGHT, down)
            KeyEvent.KEYCODE_BUTTON_THUMBL -> mouseButton(BTN_MIDDLE, down)
            KeyEvent.KEYCODE_BUTTON_X -> client.key(Keysyms.RETURN, down)
            KeyEvent.KEYCODE_BUTTON_Y -> client.key(Keysyms.BACKSPACE, down)
            KeyEvent.KEYCODE_BUTTON_SELECT -> client.key(Keysyms.ESCAPE, down)
            KeyEvent.KEYCODE_BUTTON_START -> if (down) toggleKeyboard()
            KeyEvent.KEYCODE_BUTTON_L1 -> client.key(Keysyms.SUPER, down)
            KeyEvent.KEYCODE_BUTTON_R1 -> client.key(Keysyms.ALT, down)
            KeyEvent.KEYCODE_BUTTON_L2 -> if (down != l2) { l2 = down; client.key(Keysyms.CTRL, down) }
            KeyEvent.KEYCODE_BUTTON_R2 -> if (down != r2) { r2 = down; client.key(Keysyms.TAB, down) }
            KeyEvent.KEYCODE_BUTTON_THUMBR -> if (down) onToggleBar?.invoke()
        }
    }

    companion object {
        const val BTN_WHEEL_LEFT = 32
        const val BTN_WHEEL_RIGHT = 64
        const val BTN_LEFT = 1
        const val BTN_MIDDLE = 2
        const val BTN_RIGHT = 4
        const val BTN_WHEEL_UP = 8
        const val BTN_WHEEL_DOWN = 16
    }
}

object Keysyms {
    const val BACKSPACE = 0xff08
    const val TAB = 0xff09
    const val RETURN = 0xff0d
    const val ESCAPE = 0xff1b
    const val HOME = 0xff50
    const val LEFT = 0xff51
    const val UP = 0xff52
    const val RIGHT = 0xff53
    const val DOWN = 0xff54
    const val PAGE_UP = 0xff55
    const val PAGE_DOWN = 0xff56
    const val END = 0xff57
    const val INSERT = 0xff63
    const val DELETE = 0xffff
    const val SHIFT = 0xffe1
    const val CTRL = 0xffe3
    const val ALT = 0xffe9
    const val SUPER = 0xffeb
    const val F1 = 0xffbe
    val MODIFIERS = setOf(SHIFT, 0xffe2, CTRL, 0xffe4, ALT, 0xffea, SUPER, 0xffec)

    fun forChar(code: Int): Int = when {
        code in 0x20..0x7e || code in 0xa0..0xff -> code
        code < 0x20 -> code or 0xff00
        else -> 0x01000000 or code
    }

    private val special = mapOf(
        KeyEvent.KEYCODE_ENTER to RETURN, KeyEvent.KEYCODE_NUMPAD_ENTER to RETURN,
        KeyEvent.KEYCODE_DEL to BACKSPACE, KeyEvent.KEYCODE_FORWARD_DEL to DELETE,
        KeyEvent.KEYCODE_TAB to TAB, KeyEvent.KEYCODE_ESCAPE to ESCAPE,
        KeyEvent.KEYCODE_DPAD_LEFT to LEFT, KeyEvent.KEYCODE_DPAD_RIGHT to RIGHT,
        KeyEvent.KEYCODE_DPAD_UP to UP, KeyEvent.KEYCODE_DPAD_DOWN to DOWN,
        KeyEvent.KEYCODE_MOVE_HOME to HOME, KeyEvent.KEYCODE_MOVE_END to END,
        KeyEvent.KEYCODE_PAGE_UP to PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN to PAGE_DOWN,
        KeyEvent.KEYCODE_INSERT to INSERT,
        KeyEvent.KEYCODE_SHIFT_LEFT to SHIFT, KeyEvent.KEYCODE_SHIFT_RIGHT to 0xffe2,
        KeyEvent.KEYCODE_CTRL_LEFT to CTRL, KeyEvent.KEYCODE_CTRL_RIGHT to 0xffe4,
        KeyEvent.KEYCODE_ALT_LEFT to ALT, KeyEvent.KEYCODE_ALT_RIGHT to 0xffea,
        KeyEvent.KEYCODE_META_LEFT to SUPER, KeyEvent.KEYCODE_META_RIGHT to 0xffec,
    ) + (0..11).associate { (KeyEvent.KEYCODE_F1 + it) to (F1 + it) }

    fun forKeyEvent(e: KeyEvent): Int? {
        special[e.keyCode]?.let { return it }
        // Ignore Ctrl/Alt for the character itself; they're sent as separate modifier keys.
        val ch = e.getUnicodeChar(e.metaState and (KeyEvent.META_SHIFT_MASK or KeyEvent.META_CAPS_LOCK_ON))
        return if (ch > 0) forChar(ch) else null
    }
}
