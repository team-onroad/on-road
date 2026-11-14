package com.project.on_road.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 온로드 디자인 컬러 토큰 (디자인 SVG 기준). 새 화면도 이 값만 사용한다.
 * 회색은 한 계열(zinc)로 통일했다: 예전에 섞여 있던 푸른 회색(slate) 값은 같은 역할의 zinc 값으로 맞췄고,
 * 이름은 기존 코드가 깨지지 않게 그대로 둔다 (같은 값을 가리키는 이름은 주석에 = 로 적었다).
 */
object OnRoadColors {
    // Brand
    val Primary = Color(0xFF0F172A)
    val PrimaryDeep = Color(0xFF0F172A)        // 채팅 전송 버튼
    val PrimarySoft = Color(0x0D5F82D6)          // 연한 태그·선택 배경
    val PrimaryTint = Color(0x1A0F172A)        // Primary 10%
    val PrimaryOutlineSoft = Color(0xFFE2E3F0) // AI 말풍선 테두리
    val Point = Color(0xFF34D399)              // 포인트 (진행 막대·알림 배지·강조 점)

    // Text
    val TextPrimary = Color(0xFF0F172A)
    val TextStrong = Color(0xFF0F172A)
    val TextSecondary = Color(0xFF3F3F46)
    val TextTertiary = Color(0xFF71717A)
    val TextQuaternary = Color(0xFFA1A1AA)
    val TextSlate = Color(0xFF52525B)        // 통일: 회색 한 계열(zinc)로
    val TextSlateDark = Color(0xFF3F3F46)    // = TextSecondary
    val TextSlateMid = Color(0xFF71717A)     // = TextTertiary
    val TextMuted = Color(0xFFA1A1AA)        // = TextQuaternary

    // Surface
    val Background = Color.White
    val Surface = Color.White
    val SurfaceSubtle = Color(0xFFFAFAFA)
    val SurfaceGuide = Color(0xFFFAFAFA)     // = SurfaceSubtle
    val SurfaceMuted = Color(0xFFF4F4F5)
    val SurfaceSlate = Color(0xFFF4F4F5)     // = SurfaceMuted
    val SurfaceCool = Color(0xFFFAFAFA)      // = SurfaceSubtle
    val SurfaceFile = Color(0xFFECEEF0)
    val SurfaceBadge = Color(0xFFE0E3E5)
    val SurfaceSlateTag = Color(0xFFE4E4E7)  // = Border

    // Border
    val Border = Color(0xFFE4E4E7)
    val BorderSlate = Color(0xFFE4E4E7)      // = Border
    val BorderChip = Color(0xFFD4D4D8)       // 진한 테두리 (완료 태그·입력칸)
    val BorderInput = Color(0xFFE4E4E7)      // = Border
    val BorderSage = Color(0xFFD4D4D8)       // = BorderChip
    val BorderDark = Color(0xFF707279)
    val BorderSearch = Color(0xB3A1A1AA)
    val Divider = Color(0xFFF4F4F5)

    // Status
    val Danger = Color(0xFFE85E5E)
    val DangerSoft = Color(0x0DE85E5E)
    val DangerTint = Color(0xFFFDF1F1)
    val Required = Color(0xFFE64A4A)
    val Warning = Color(0xFFB7791F)
    val WarningSoft = Color(0xFFFDF6E9)

    // 차트·분류용 보조색 (메인 녹색과 어울리는 저채도)
    val ChartSage = Color(0xFFFCFDFD)
    val ChartSand = Color(0xFFD9B26F)
    val ChartSky = Color(0xFF7A9CC6)
    val ChartClay = Color(0xFFC98B7A)

    // 아동·청소년 화면 포인트 (캐릭터 별 색)
    val Star = Color(0xFFF4B740)
}
