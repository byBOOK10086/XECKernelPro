package me.weishu.kernelsu.ui.screen.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.design.token.Xc
import me.weishu.kernelsu.ui.screen.module.ModuleBackgrounds
import me.weishu.kernelsu.ui.screen.module.rememberBackgroundBitmap
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/**
 * Visual editor for the home status card: image, blur and dim.
 *
 * The image itself remains in [ModuleBackgrounds] (the same slot / decoder /
 * LruCache as module cards); only the two render knobs live in
 * [HomeCardAppearanceStore]. That keeps the storage format the module cards
 * already understand and avoids a second image pipeline.
 */
@Composable
fun HomeAppearanceDialog(
    backgroundUri: String?,
    appearance: HomeCardAppearance,
    onAppearanceChange: (HomeCardAppearance) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val image = rememberBackgroundBitmap(backgroundUri)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            ModuleBackgrounds.set(context, HOME_STATUS_BACKGROUND_SLOT, uri)
            onAppearanceChange(appearance)
        }
    }
    var blur by remember(appearance) { mutableStateOf(appearance.blur) }
    var dim by remember(appearance) { mutableStateOf(appearance.dim) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(Xc.shapes.xl)
                .background(Xc.colors.surface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.home_appearance_title),
                color = Xc.colors.text,
            )
            Text(
                text = stringResource(R.string.home_appearance_summary),
                color = Xc.colors.textSecondary,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp)
                    .clip(Xc.shapes.md)
                    .background(
                        if (image == null) {
                            Brush.linearGradient(
                                listOf(
                                    Xc.colors.surfaceMuted,
                                    Xc.colors.accentSoft,
                                )
                            )
                        } else {
                            Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                            .then(
                                if (blur > 0f) {
                                    Modifier.blur(
                                        radius = blur.dp,
                                        edgeTreatment = BlurredEdgeTreatment.Unbounded,
                                    )
                                } else {
                                    Modifier
                                }
                            ),
                    )
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = dim.coerceIn(0f, 0.65f))),
                    )
                }
                Text(
                    text = if (image == null) {
                        stringResource(R.string.home_appearance_preview_empty)
                    } else {
                        stringResource(R.string.home_appearance_preview)
                    },
                    color = if (image == null) Xc.colors.textSecondary else Color.White,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    text = if (image == null) {
                        stringResource(R.string.home_appearance_pick)
                    } else {
                        stringResource(R.string.home_appearance_replace)
                    },
                    onClick = { picker.launch(arrayOf("image/*")) },
                )
                if (image != null) {
                    TextButton(
                        text = stringResource(R.string.home_appearance_clear),
                        onClick = {
                            ModuleBackgrounds.clear(context, HOME_STATUS_BACKGROUND_SLOT)
                            onAppearanceChange(HomeCardAppearance(blur = 0f, dim = 0.12f))
                        },
                    )
                }
            }

            Text(
                text = stringResource(R.string.home_appearance_blur, blur.toInt()),
                color = Xc.colors.textSecondary,
            )
            Slider(
                value = blur,
                onValueChange = {
                    blur = it
                    onAppearanceChange(HomeCardAppearance(blur = it, dim = dim))
                },
                valueRange = 0f..24f,
                steps = 23,
                colors = SliderDefaults.colors(
                    thumbColor = Xc.colors.accent,
                    activeTrackColor = Xc.colors.accent,
                ),
            )

            Text(
                text = stringResource(R.string.home_appearance_dim, (dim * 100).toInt()),
                color = Xc.colors.textSecondary,
            )
            Slider(
                value = dim,
                onValueChange = {
                    dim = it
                    onAppearanceChange(HomeCardAppearance(blur = blur, dim = it))
                },
                valueRange = 0f..0.65f,
                colors = SliderDefaults.colors(
                    thumbColor = Xc.colors.accent,
                    activeTrackColor = Xc.colors.accent,
                ),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    text = stringResource(R.string.home_appearance_done),
                    onClick = onDismiss,
                )
            }
        }
    }
}
