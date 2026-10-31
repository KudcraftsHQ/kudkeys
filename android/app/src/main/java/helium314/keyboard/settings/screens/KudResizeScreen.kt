// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.keyboard.KeyboardView
import helium314.keyboard.kudkeys.KudResize
import helium314.keyboard.latin.R
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.createPrefKeyForBooleanSettings
import helium314.keyboard.latin.settings.findIndexOfDefaultSetting
import helium314.keyboard.latin.utils.FoldableUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import kotlin.math.roundToInt

private val HANDLE_BLUE = Color(0xFF4E6CF0)

/**
 * Kud Keys: Layout & keys → Resize, SwiftKey's "Select your size". The keyboard (a picture of the current one) sits
 * at the bottom in a blue frame: drag the top, bottom, left or right handle to resize, the centre one to move it.
 * OK saves HeliBoard's height, side padding and bottom padding scales for the current orientation.
 */
@Composable
fun KudResizeScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { KudResizeStore(ctx) }
    var resize by remember { mutableStateOf(store.read()) }
    SearchSettingsScreen(onClickBack = onClickBack, title = stringResource(R.string.kud_resize_title), settings = emptyList()) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF101010))) {
            val density = LocalDensity.current
            val widthPx = constraints.maxWidth.toFloat()
            val m = remember(widthPx) { store.metrics(widthPx) }
            val saved = remember { store.read() }
            val keys = remember(widthPx) { KudKeyboardPreview.renderKeys(ctx, widthPx.roundToInt(), saved, m) }
            val heightPx = resize.keyboardHeight(m)
            val side = resize.sidePadding(m)
            val keysHeight = resize.keysHeight(m)
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
                Text(
                    stringResource(R.string.kud_resize_hint),
                    color = Color(0xFFB0B0B0),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                Box(Modifier.fillMaxWidth().height(with(density) { heightPx.toDp() }).background(store.keyboardBackground)) {
                    // the keys area, with its frame and handles
                    Box(
                        Modifier
                            .offset { IntOffset(side.roundToInt(), 0) }
                            .width(with(density) { (widthPx - 2 * side).toDp() })
                            .height(with(density) { keysHeight.toDp() })
                    ) {
                        if (keys != null)
                            Image(keys.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        Box(Modifier.fillMaxSize().border(2.dp, HANDLE_BLUE))
                        Handle(Modifier.align(Alignment.TopCenter).offset(y = (-14).dp), 48, 28, R.drawable.kud_ic_menu) { _, dy ->
                            resize = resize.dragTop(dy, m)
                        }
                        Handle(Modifier.align(Alignment.BottomCenter).offset(y = 14.dp), 48, 28, R.drawable.kud_ic_menu) { _, dy ->
                            resize = resize.dragBottom(dy, m)
                        }
                        Handle(Modifier.align(Alignment.CenterStart).offset(x = (-2).dp), 16, 48, null) { dx, _ ->
                            resize = resize.dragSide(dx, m)
                        }
                        Handle(Modifier.align(Alignment.CenterEnd).offset(x = 2.dp), 16, 48, null) { dx, _ ->
                            resize = resize.dragSide(-dx, m)
                        }
                        Handle(Modifier.align(Alignment.Center), 56, 56, R.drawable.kud_ic_move, circle = true) { _, dy ->
                            resize = resize.move(dy, m)
                        }
                        PillButton(stringResource(R.string.kud_resize_reset), Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 36.dp)) {
                            resize = KudResize.DEFAULT
                        }
                        PillButton(stringResource(R.string.kud_resize_ok), Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = 36.dp)) {
                            store.save(resize)
                            onClickBack()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Handle(modifier: Modifier, widthDp: Int, heightDp: Int, icon: Int?, circle: Boolean = false, onDrag: (Float, Float) -> Unit) {
    Box(
        modifier
            .size(widthDp.dp, heightDp.dp)
            .background(HANDLE_BLUE, if (circle) CircleShape else RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onDrag(drag.x, drag.y)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(painterResource(icon), null, tint = Color.White, modifier = Modifier.size(if (circle) 30.dp else 22.dp))
        else Box(Modifier.size(3.dp, 26.dp).background(Color.White))
    }
}

@Composable
private fun PillButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.width(120.dp).height(44.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
    ) { Text(text, fontSize = 17.sp) }
}

/** Reads and writes the three scales for the current orientation (and fold / split state), like HeliBoard does. */
class KudResizeStore(private val context: Context) {
    private val prefs: SharedPreferences = context.prefs()
    private val landscape get() = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private val folded get() = FoldableUtils.isFolded
    private val split get() = Settings.getValues()?.mIsSplitKeyboardEnabled == true

    val keyboardBackground: Color
        get() = Settings.getValues()?.mColors?.get(ColorType.MAIN_BACKGROUND)?.let { Color(it) } ?: Color(0xFF171717)

    fun read() = KudResize(
        Settings.readHeightScale(prefs, landscape, folded),
        Settings.readSidePaddingScale(prefs, landscape, split, folded),
        Settings.readBottomPaddingScale(prefs, landscape, folded),
    )

    fun save(resize: KudResize) {
        val i2 = findIndexOfDefaultSetting(landscape, folded)
        val i3 = findIndexOfDefaultSetting(landscape, split, folded)
        prefs.edit {
            putFloat(createPrefKeyForBooleanSettings(Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX, i2, 2), resize.heightScale)
            putFloat(createPrefKeyForBooleanSettings(Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX, i2, 2), resize.bottomScale)
            putFloat(createPrefKeyForBooleanSettings(Settings.PREF_SIDE_PADDING_SCALE_PREFIX, i3, 3), resize.sideScale)
        }
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
    }

    /** Pixel metrics for [width]: the default height and the padding fractions of the keyboard theme. */
    fun metrics(width: Float): KudResize.Metrics {
        val themeContext = ContextThemeWrapper(context, KeyboardTheme.getKeyboardTheme(context).mStyleId)
        val a = themeContext.obtainStyledAttributes(null, R.styleable.Keyboard, R.attr.keyboardStyle, R.style.Keyboard)
        val side = a.getFraction(R.styleable.Keyboard_keyboardLeftPadding, 1, 1, 0f)
        val bottom = a.getFraction(R.styleable.Keyboard_keyboardBottomPadding, 1, 1, 0f)
        a.recycle()
        val numberRow = Settings.getValues()?.mShowsNumberRow == true
        return KudResize.Metrics(width, ResourceUtils.getDefaultKeyboardHeight(context.resources, numberRow).toFloat(), side, bottom)
    }
}

/** A picture of the current keyboard's keys, for the Resize screen. */
object KudKeyboardPreview {
    /** Renders the alphabet keyboard at the saved size and returns only its keys area (paddings cut off). */
    fun renderKeys(context: Context, width: Int, saved: KudResize, m: KudResize.Metrics): Bitmap? = try {
        val sv = Settings.getValues()
        val themeContext = ContextThemeWrapper(context, KeyboardTheme.getKeyboardTheme(context).mStyleId)
        val height = saved.keyboardHeight(m).roundToInt()
        val layoutSet = KeyboardLayoutSet.Builder(themeContext, EditorInfo(), sv)
            .setKeyboardGeometry(width, height)
            .setSubtype(RichInputMethodManager.getInstance().currentSubtype)
            .build()
        val view = KeyboardView(themeContext, null)
        view.setKeyboard(layoutSet.getKeyboard(KeyboardElement.ALPHABET))
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val full = Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(full)
        canvas.drawColor(sv.mColors.get(ColorType.MAIN_BACKGROUND))
        view.draw(canvas)
        val side = saved.sidePadding(m).roundToInt().coerceIn(0, width / 3)
        val keysHeight = saved.keysHeight(m).roundToInt().coerceIn(1, full.height)
        Bitmap.createBitmap(full, side, 0, (full.width - 2 * side).coerceAtLeast(1), keysHeight)
    } catch (e: Exception) {
        Log.w("KudKeyboardPreview", "could not draw the keyboard", e)
        null
    }
}
