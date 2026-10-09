package com.project.on_road.ui.theme

import androidx.compose.ui.graphics.Color

/** 온로드 디자인 컬러 토큰 (디자인 SVG 기준). 새 화면도 이 값만 사용한다. */
object OnRoadColors {
    // Brand
    val Primary = Color(0xFF0F172A)
    val PrimaryDeep = Color(0xFF0F172A)        // 채팅 전송 버튼
    val PrimarySoft = Color(0x0D5F82D6)          // 연한 태그·선택 배경
    val PrimaryTint = Color(0x1A0F172A)        // Primary 10%
    val PrimaryOutlineSoft = Color(0xFFE2E3F0) // AI 말풍선 테두리

    // Text
    val TextPrimary = Color(0xFF0F172A)
    val TextStrong = Color(0xFF0F172A)
    val TextSecondary = Color(0xFF3F3F46)
    val TextTertiary = Color(0xFF71717A)
    val TextQuaternary = Color(0xFFA1A1AA)
    val TextSlate = Color(0xFF565E74)
    val TextSlateDark = Color(0xFF334155)
    val TextSlateMid = Color(0xFF64748B)
    val TextMuted = Color(0xFF94A3B8)

    // Surface
    val Background = Color.White
    val Surface = Color.White
    val SurfaceSubtle = Color(0xFFFAFAFA)
    val SurfaceGuide = Color(0xFFFBFBFB)
    val SurfaceMuted = Color(0xFFF2F4F6)
    val SurfaceSlate = Color(0xFFF1F5F9)
    val SurfaceCool = Color(0xFFF8FAFC)
    val SurfaceFile = Color(0xFFECEEF0)
    val SurfaceBadge = Color(0xFFE0E3E5)
    val SurfaceSlateTag = Color(0xFFE2E8F0)

    // Border
    val Border = Color(0xFFE4E4E7)
    val BorderSlate = Color(0xFFE2E8F0)
    val BorderChip = Color(0xFFD6D6D6)
    val BorderInput = Color(0xFFE6E8EA)
    val BorderSage = Color(0xFFBFC3C9)
    val BorderDark = Color(0xFF707279)
    val BorderSearch = Color(0xB3A1A1AA)
    val Divider = Color(0xFFF1F5F9)

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
