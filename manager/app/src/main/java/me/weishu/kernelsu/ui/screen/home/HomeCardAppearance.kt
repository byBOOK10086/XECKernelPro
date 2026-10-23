package me.weishu.kernelsu.ui.screen.home

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** The same slot used by [me.weishu.kernelsu.ui.screen.module.ModuleBackgrounds]. */
const val HOME_STATUS_BACKGROUND_SLOT = "home_status"

/**
 * Per-card render style. Only the home status card uses it today, but the
 * store is keyed by slot so a second card can opt in without another pref.
 */
data class HomeCardAppearance(
    val blur: Float = 0f,
    val dim: Float = 0.12f,
) {
    fun normalized(): HomeCardAppearance = copy(
        blur = blur.coerceIn(0f, 24f),
        dim = dim.coerceIn(0f, 0.65f),
    )
}

object HomeCardAppearanceStore {
    private const val PREFS = "home_card_appearance"

    fun load(context: Context, slot: String = HOME_STATUS_BACKGROUND_SLOT): HomeCardAppearance {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return HomeCardAppearance(
            blur = prefs.getFloat("$slot.blur", 0f),
            dim = prefs.getFloat("$slot.dim", 0.12f),
        ).normalized()
    }

    fun save(
        context: Context,
        appearance: HomeCardAppearance,
        slot: String = HOME_STATUS_BACKGROUND_SLOT,
    ) {
        val value = appearance.normalized()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat("$slot.blur", value.blur)
            .putFloat("$slot.dim", value.dim)
            .apply()
    }
}

@Composable
fun rememberHomeCardAppearance(): MutableState<HomeCardAppearance> {
    val context = LocalContext.current
    return remember(context) {
        mutableStateOf(HomeCardAppearanceStore.load(context))
    }
}
