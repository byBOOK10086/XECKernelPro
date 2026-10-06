package me.weishu.kernelsu.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Keep page content present while Navigation3 moves the entry. Delaying the
 * first frame leaves the destination background visible on its own, which is
 * perceived as a white flash on entry and a black frame on exit. Expensive
 * lists still keep their own loading states; navigation must not swap the
 * entire page for a placeholder.
 */
@Composable
fun rememberContentReady(): Boolean = remember { true }
