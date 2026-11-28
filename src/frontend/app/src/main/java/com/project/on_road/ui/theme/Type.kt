package com.project.on_road.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.project.on_road.R

/** res/font 의 Pretendard (regular / medium / semi_bold / bold .otf) */
val Pretendard = FontFamily(
    Font(R.font.regular, FontWeight.Normal),
    Font(R.font.medium, FontWeight.Medium),
    Font(R.font.semi_bold, FontWeight.SemiBold),
    Font(R.font.bold, FontWeight.Bold)
)

private fun style(size: Int, weight: FontWeight, lineHeight: Float = 1.4f) = TextStyle(
    fontFamily = Pretendard,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = (size * lineHeight).sp,
    letterSpacing = (-0.02).em,
)

/** 온로드 타입 스케일 */
object OnRoadType {
    val Hero = style(32, FontWeight.Bold, 1.25f)           // D-day 숫자 등 큰 수치
    val Display = style(24, FontWeight.Bold, 1.35f)        // 홈 인사말, 온보딩 타이틀
    val DisplayRegular = style(24, FontWeight.Normal, 1.35f)
    val Title1 = style(22, FontWeight.Bold, 1.35f)         // 카드 큰 제목, 화면 헤드라인
    val Title1Regular = style(22, FontWeight.Normal, 1.35f)
    val Title2 = style(20, FontWeight.Bold, 1.35f)         // 섹션 제목
    val Title3 = style(18, FontWeight.Bold)                // 정책 카드 제목
    val Headline = style(17, FontWeight.SemiBold)
    val Body1 = style(16, FontWeight.Medium)               // 버튼
    val Body2 = style(15, FontWeight.Normal)
    val Body3 = style(14, FontWeight.Normal, 1.55f)        // 본문, 말풍선
    val Label = style(13, FontWeight.Bold)                 // 입력 라벨
    val Caption = style(13, FontWeight.Normal)
    val Caption2 = style(12, FontWeight.Normal)
    val Micro = style(11, FontWeight.Normal)
    val Tiny = style(10, FontWeight.Normal)
}

val OnRoadTypography = Typography(
    displaySmall = OnRoadType.Hero,
    headlineMedium = OnRoadType.Display,
    headlineSmall = OnRoadType.Title1,
    titleLarge = OnRoadType.Title2,
    titleMedium = OnRoadType.Title3,
    titleSmall = OnRoadType.Headline,
    bodyLarge = OnRoadType.Body2,
    bodyMedium = OnRoadType.Body3,
    bodySmall = OnRoadType.Caption2,
    labelLarge = OnRoadType.Body1,
    labelMedium = OnRoadType.Label,
    labelSmall = OnRoadType.Micro,
)
