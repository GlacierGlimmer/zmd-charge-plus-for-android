package com.glacierglimmer.endfieldchargeplus.ui.components

import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.glacierglimmer.endfieldchargeplus.core.model.HudRenderData
import com.glacierglimmer.endfieldchargeplus.overlay.OverlayHudView

/**
 * Live HUD preview.
 *
 * The preview reuses the real overlay renderer through [AndroidView]; there is deliberately no
 * second drawing implementation, so what the user sees here is what the floating window draws.
 */
@Composable
fun HudPreviewCard(
    data: HudRenderData,
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String? = null,
    heightDp: Int = 168,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(modifier = Modifier.padding(vertical = 10.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
            val previewScale = (maxWidth.value * 0.94f / OverlayHudView.DESIGN_VIEW_WIDTH)
                .coerceIn(0.1f, 4f)
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heightDp.dp),
                factory = { context ->
                    OverlayHudView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        setScaledDensity(previewScale)
                        setRenderData(data)
                    }
                },
                update = { view ->
                    view.setScaledDensity(previewScale)
                    view.setRenderData(data)
                },
            )
            }
        }
    }
}
