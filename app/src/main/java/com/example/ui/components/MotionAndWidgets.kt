package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.theme.VaultBlue
import com.example.ui.theme.VaultBlueDeep
import com.example.ui.theme.VaultDarkBackground
import com.example.ui.theme.VaultDarkCard
import com.example.ui.theme.VaultDarkCardElevated
import com.example.ui.theme.VaultHairlineBorder

/**
 * Adds a smooth, spring-animated press scale (0.96f) + subtle haptic tick + Material ripple.
 */
fun Modifier.tactileClickable(
  enabled: Boolean = true,
  scaleOnPress: Float = 0.96f,
  withHaptic: Boolean = true,
  onClick: () -> Unit,
): Modifier = composed {
  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()
  val haptics = LocalHapticFeedback.current
  val scale by
    animateFloatAsState(
      targetValue = if (isPressed && enabled) scaleOnPress else 1f,
      animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 550f),
      label = "tactileScale",
    )

  this.graphicsLayer {
      scaleX = scale
      scaleY = scale
    }
    .clickable(
      enabled = enabled,
      interactionSource = interactionSource,
      indication = ripple(),
      onClick = {
        if (withHaptic) {
          runCatching { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        }
        onClick()
      },
    )
}

/**
 * Signature Tonkeeper-style circular thumb action button with icon + centered label below.
 */
@Composable
fun TonkeeperActionCircle(
  icon: ImageVector,
  label: String,
  primary: Boolean,
  tag: String,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()
  val haptics = LocalHapticFeedback.current
  val scale by
    animateFloatAsState(
      targetValue = if (isPressed) 0.92f else 1f,
      animationSpec = spring(dampingRatio = 0.6f, stiffness = 520f),
      label = "actionCircleScale",
    )

  Column(
    modifier =
      modifier
        .minimumInteractiveComponentSize()
        .graphicsLayer {
          scaleX = scale
          scaleY = scale
        }
        .clip(RoundedCornerShape(16.dp))
        .clickable(
          interactionSource = interactionSource,
          indication = ripple(bounded = false),
          onClick = {
            runCatching { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
            onClick()
          },
        )
        .padding(vertical = 6.dp)
        .testTag(tag),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Box(
      modifier =
        Modifier.size(54.dp)
          .clip(CircleShape)
          .background(
            if (primary) {
              Brush.linearGradient(listOf(VaultBlue, VaultBlueDeep))
            } else {
              Brush.linearGradient(listOf(VaultDarkCardElevated, VaultDarkCard))
            }
          ),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = label,
        tint = if (primary) Color(0xFF001E30) else VaultBlue,
        modifier = Modifier.size(24.dp),
      )
    }
    Spacer(modifier = Modifier.height(7.dp))
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      fontWeight = FontWeight.SemiBold,
      color = if (primary) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
fun VaultGlassCard(
  modifier: Modifier = Modifier,
  shape: Shape = RoundedCornerShape(22.dp),
  containerColor: Color = VaultDarkCard,
  content: @Composable ColumnScope.() -> Unit,
) {
  Card(
    modifier = modifier,
    shape = shape,
    colors = CardDefaults.cardColors(containerColor = containerColor),
    border = BorderStroke(1.dp, VaultHairlineBorder),
    content = content,
  )
}

/**
 * Thumb-zone bottom bar pinned safely above gesture navigation bar & IME keyboard.
 */
@Composable
fun BottomThumbActionBar(
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  Surface(
    color = VaultDarkBackground.copy(alpha = 0.96f),
    modifier = modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      HorizontalDivider(color = VaultHairlineBorder)
      Box(
        modifier =
          Modifier.fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
      ) {
        Column(
          modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(8.dp),
          content = content,
        )
      }
    }
  }
}

/**
 * Smoothly animates numeric balance updates without layout jitter.
 */
@Composable
fun AnimatedBalanceText(
  text: String,
  style: TextStyle,
  color: Color,
  modifier: Modifier = Modifier,
  fontWeight: FontWeight = FontWeight.Bold,
) {
  AnimatedContent(
    targetState = text,
    transitionSpec = {
      (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 4 }) togetherWith
        (fadeOut(tween(150)) + slideOutVertically(tween(150)) { -it / 4 }) using
        SizeTransform(clip = false)
    },
    label = "balanceTextTransition",
  ) { value ->
    Text(
      text = value,
      style = style,
      fontWeight = fontWeight,
      color = color,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = modifier,
    )
  }
}
