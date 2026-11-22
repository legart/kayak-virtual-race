package dk.kodeologi.virtualrace.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Arcade Kayak Sprint Regatta color palette, lane boat styling, and reusable UI components.
 */
object RegattaTheme {
    // Deep arcade backgrounds & frames
    val DeepNavyBg = Color(0xFF0E1B38)
    val ArcadePanelBg = Color(0xFF172A54)
    val ArcadePanelLight = Color(0xFF213B73)
    val ArcadeBorderGold = Color(0xFFFFD600)

    // Water & regatta track colors
    val WaterDeep = Color(0xFF0277BD)
    val WaterMid = Color(0xFF039BE5)
    val WaterBright = Color(0xFF29B6F6)
    val WaterFoam = Color(0xFFE1F5FE)

    // High-contrast regatta accent colors
    val AlertRed = Color(0xFFFF1744)
    val SuccessGreen = Color(0xFF00E676)
    val AccentBlue = Color(0xFF2979FF)
    val AccentYellow = Color(0xFFFFEA00)
    val AccentPurple = Color(0xFFD500F9)

    val GoldTrophy = Color(0xFFFFD700)
    val SilverTrophy = Color(0xFFE0E0E0)
    val BronzeTrophy = Color(0xFFCD7F32)

    data class KayakLaneStyle(
        val laneIndex: Int,
        val hullPrimary: Color,
        val hullSecondary: Color,
        val helmetColor: Color,
        val badgeColor: Color
    ) {
        val laneNumber: Int get() = laneIndex + 1
        val displayTitle: String get() = "Lane $laneNumber"
    }

    val laneStyles: List<KayakLaneStyle> = listOf(
        KayakLaneStyle(0, Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFFFF1744), Color(0xFFFF1744)),
        KayakLaneStyle(1, Color(0xFF43A047), Color(0xFF1565C0), Color(0xFF00E676), Color(0xFF00E676)),
        KayakLaneStyle(2, Color(0xFFF06292), Color(0xFFFFF176), Color(0xFFFF80AB), Color(0xFFFF4081)),
        KayakLaneStyle(3, Color(0xFF7CB342), Color(0xFFFFFFFF), Color(0xFF76FF03), Color(0xFF76FF03)),
        KayakLaneStyle(4, Color(0xFF1E88E5), Color(0xFFFFEE58), Color(0xFFFFFFFF), Color(0xFF40C4FF)),
        KayakLaneStyle(5, Color(0xFFFB8C00), Color(0xFF2E7D32), Color(0xFFFF6D00), Color(0xFFFF9100)),
        KayakLaneStyle(6, Color(0xFFFDD835), Color(0xFF8E24AA), Color(0xFFFFEA00), Color(0xFFFFEA00)),
        KayakLaneStyle(7, Color(0xFF8D6E63), Color(0xFFE53935), Color(0xFFD7CCC8), Color(0xFFFFAB40))
    )

    fun styleForLane(laneIndex: Int): KayakLaneStyle =
        laneStyles[laneIndex.coerceIn(0, laneStyles.lastIndex)]
}

/**
 * Chunky glossy 3D arcade button.
 */
@Composable
fun ArcadeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    topColor: Color = Color(0xFFFFCA28),
    bottomColor: Color = Color(0xFFFF8F00),
    borderColor: Color = Color.White,
    textColor: Color = Color(0xFF1A1A1A),
    enabled: Boolean = true,
    fontSize: TextUnit = 13.sp,
    horizontalPadding: Dp = 14.dp,
    verticalPadding: Dp = 7.dp
) {
    val bgBrush = if (enabled) {
        Brush.verticalGradient(listOf(topColor, bottomColor))
    } else {
        Brush.verticalGradient(listOf(Color(0xFF546E7A), Color(0xFF37474F)))
    }
    val actualTextColor = if (enabled) textColor else Color(0xFFB0BEC5)
    val actualBorder = if (enabled) borderColor else Color(0xFF78909C)

    Box(
        modifier = modifier
            .shadow(if (enabled) 4.dp else 1.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(bgBrush)
            .border(2.dp, actualBorder, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = actualTextColor,
            fontSize = fontSize,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.SansSerif
        )
    }
}

/**
 * Pill badge used in HUD headers and status cards.
 */
@Composable
fun ArcadeBadge(
    text: String,
    bgColor: Color,
    textColor: Color = Color.White,
    borderColor: Color = Color.White.copy(alpha = 0.7f),
    fontSize: TextUnit = 11.sp,
    horizontalPadding: Dp = 8.dp,
    verticalPadding: Dp = 3.dp,
    modifier: Modifier = Modifier
) {
    Surface(
        color = bgColor,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.5.dp, borderColor),
        modifier = modifier
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = fontSize,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding)
        )
    }
}
