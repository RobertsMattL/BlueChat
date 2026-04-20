package com.codeflow.bluechat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codeflow.bluechat.model.ReceivingMessage
import kotlinx.coroutines.delay
import kotlin.random.Random

private const val GRID_SIZE = 3
private const val TICK_INTERVAL_MS = 140L

/**
 * Placeholder bubble shown while a chunked message is being reassembled.
 */
@Composable
fun ReceivingBubble(
    receiving: ReceivingMessage,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            modifier = Modifier
                .wrapContentWidth()
                .widthIn(max = 220.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "receiving chunk ${receiving.receivedChunks} of ${receiving.totalChunks}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.width(8.dp))

                BinaryGrid(
                    seedSalt = receiving.messageId,
                    receivedChunks = receiving.receivedChunks,
                    totalChunks = receiving.totalChunks
                )
            }
        }
    }
}

/**
 * Minimalist 3x3 grid of flickering 0s and 1s. The number of "active"
 * (bright) cells tracks chunk-reception progress.
 */
@Composable
private fun BinaryGrid(
    seedSalt: Int,
    receivedChunks: Int,
    totalChunks: Int
) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_INTERVAL_MS)
            tick++
        }
    }

    val activeCells = if (totalChunks > 0) {
        ((receivedChunks.toFloat() / totalChunks.toFloat()) * (GRID_SIZE * GRID_SIZE))
            .toInt()
            .coerceIn(0, GRID_SIZE * GRID_SIZE)
    } else 0

    val activeColor = MaterialTheme.colorScheme.onSecondaryContainer
    val dimColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f)

    val text = remember(tick, seedSalt, activeCells) {
        buildBinaryGrid(
            size = GRID_SIZE,
            seedSalt = seedSalt,
            activeCells = activeCells,
            tick = tick,
            activeColor = activeColor,
            dimColor = dimColor
        )
    }

    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = 4.sp,
        lineHeight = 5.sp
    )
}

private fun buildBinaryGrid(
    size: Int,
    seedSalt: Int,
    activeCells: Int,
    tick: Int,
    activeColor: Color,
    dimColor: Color
): AnnotatedString {
    val rng = Random(seedSalt.toLong() xor (tick.toLong() * 2654435761L))
    val total = size * size
    val activeIndices = buildSet {
        val shuffled = (0 until total).shuffled(rng)
        for (i in 0 until activeCells) add(shuffled[i])
    }
    return buildAnnotatedString {
        for (r in 0 until size) {
            for (c in 0 until size) {
                val idx = r * size + c
                val bit = if (rng.nextBoolean()) '1' else '0'
                val color = if (idx in activeIndices) activeColor else dimColor
                withStyle(SpanStyle(color = color)) {
                    append(bit)
                }
                if (c < size - 1) append(' ')
            }
            if (r < size - 1) append('\n')
        }
    }
}
