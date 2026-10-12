package com.project.on_road.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** 간격 · 모서리 토큰 */
object OnRoadDimens {
    val ScreenPadding = 24.dp
    val CardPadding = 24.dp
    val SectionGap = 48.dp
    val BlockGap = 32.dp
    val TopBarHeight = 58.dp
    val ButtonHeight = 48.dp
    val ButtonHeightSmall = 34.dp
    val FieldHeight = 48.dp
}

object OnRoadShapes {
    val Card = RoundedCornerShape(16.dp)   // 큰 카드
    val Inner = RoundedCornerShape(12.dp)  // 보조 카드, 말풍선, 입력창
    val Item = RoundedCornerShape(10.dp)   // 체크리스트 항목
    val Field = RoundedCornerShape(8.dp)   // 텍스트필드, 버튼, 박스
    val Tag = RoundedCornerShape(5.dp)
    val Chip = RoundedCornerShape(4.dp)
    val Badge = RoundedCornerShape(2.dp)
}
