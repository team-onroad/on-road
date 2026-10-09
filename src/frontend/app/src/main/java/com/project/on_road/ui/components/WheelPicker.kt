package com.project.on_road.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadType
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 휠 선택기 (알람 앱처럼 위아래로 굴려서 가운데 칸을 고름).
 * - 굴리다 멈추면 가운데 칸에 딱 맞춰지고 onSelect 가 불린다
 * - 칸을 누르면 그 칸이 가운데로 온다
 * - 밖에서 selectedIndex 가 바뀌면(예: 2월이라 31일이 없어짐) 그 칸으로 이동한다
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WheelPicker(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemHeight: Dp = 56.dp,
    visibleCount: Int = 5,
    textStyle: TextStyle = OnRoadType.Title1,
) {
    val start = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val state = rememberLazyListState(initialFirstVisibleItemIndex = start)
    val fling = rememberSnapFlingBehavior(lazyListState = state)
    val scope = rememberCoroutineScope()
    val itemPx = with(LocalDensity.current) { itemHeight.toPx() }
    val half = visibleCount / 2
    val select by rememberUpdatedState(onSelect)

    // 지금 가운데에 있는 칸
    val center by remember(items.size) {
        derivedStateOf {
            val i = state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > itemPx / 2f) 1 else 0
            i.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        }
    }

    // 멈추면 선택 (처음 화면에 보일 때도 한 번)
    LaunchedEffect(state.isScrollInProgress, items.size) {
        if (!state.isScrollInProgress && items.isNotEmpty()) select(center)
    }
    // 밖에서 값이 바뀌면 그 칸으로
    LaunchedEffect(selectedIndex, items.size) {
        if (!state.isScrollInProgress && selectedIndex in items.indices && selectedIndex != center) {
            state.animateScrollToItem(selectedIndex)
        }
    }

    Box(modifier.height(itemHeight * visibleCount)) {
        // 가운데 선택 띠
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(itemHeight)
                .clip(RoundedCornerShape(14.dp))
                .background(OnRoadColors.SurfaceMuted),
        )
        LazyColumn(
            state = state,
            flingBehavior = fling,
            contentPadding = PaddingValues(vertical = itemHeight * half),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(items) { i, text ->
                val distance = abs(i - center)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { scope.launch { state.animateScrollToItem(i) } }
                        .graphicsLayer {
                            alpha = when (distance) {
                                0 -> 1f
                                1 -> 0.55f
                                else -> 0.25f
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text,
                        style = textStyle.copy(fontWeight = if (distance == 0) FontWeight.Bold else FontWeight.Medium),
                        color = OnRoadColors.TextPrimary,
                        maxLines = 1,
                    )
                }
            }
        }
        // 위아래 흐리게
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(itemHeight)
                .background(Brush.verticalGradient(listOf(Color.White, Color.White.copy(alpha = 0f)))),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(itemHeight)
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0f), Color.White))),
        )
    }
}
