package com.project.on_road.ui.child

import android.content.Intent
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.project.on_road.ui.components.SpeakOnce

/*
 * 아동 · 도움이 필요해 — 시안 D
 * 안내 편지(연두) → 믿을 수 있는 어른 → 1388 → 112 → 맨 아래 안내
 * 전화 카드는 다이얼 화면만 연다 (바로 걸리지 않음).
 */

/* ==================== 여기서 바꾸기 ==================== */
private object KidHelp {
    @DrawableRes val ImgLetter: Int? = null   // 안내 편지 왼쪽 그림
    @DrawableRes val ImgAdult: Int? = null    // 믿을 수 있는 어른 그림

    val CallBg = Color(0xFFE6EEFB)            // 1388 카드
    val CallTitle = Color(0xFF2F4E86)
    val CallSub = Color(0xFF1C2941)

    val SosBg = Color(0xFFFCE3D6)             // 112 카드
    val SosCircle = Color(0xFFB4441B)
    val SosTitle = Color(0xFF8A2E0E)
    val SosSub = Color(0xFF5C2A16)
}
/* ====================================================== */

@Composable
fun ChildHelpScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    fun dial(number: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))) }
    }

    SpeakOnce("힘들거나 무서운 일이 있으면 믿을 수 있는 어른에게 꼭 말해 줘. 너는 혼자가 아니야. 전화로 이야기하고 싶으면 1388, 지금 위험하면 112야.")

    Column(Modifier.fillMaxSize().background(RoadKit.Background)) {
        RoadSubHeader("도움이 필요해", Tones.Lime.bg, onBack = onBack)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 안내 편지
            Row(
                Modifier
                    .fillMaxWidth()
                    .handOutline(22.dp, seed = 7)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Tones.Lime.bg)
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 18.dp),
                verticalAlignment = Alignment.Top,
            ) {
                ImageSlot(Modifier.size(60.dp), Tones.Lime.slot, radius = 30.dp, image = KidHelp.ImgLetter, fill = Color.White.copy(alpha = 0.6f))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("힘들거나 무서운 일이 있으면 믿을 수 있는 어른에게 꼭 말해 줘.", style = hanaText(24.sp, lineHeight = 1.35f), color = RoadKit.Ink)
                    Spacer(Modifier.height(8.dp))
                    Text("너는 혼자가 아니야. 말해도 괜찮아.", style = hanaText(19.sp, FontWeight.Normal), color = Tones.Lime.sub)
                }
            }

            // 믿을 수 있는 어른
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 92.dp)
                    .handOutline(20.dp, seed = 21)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ImageSlot(Modifier.size(54.dp), RoadKit.Road, radius = 27.dp, image = KidHelp.ImgAdult)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("믿을 수 있는 어른", style = hanaText(23.sp, lineHeight = 1.25f), color = RoadKit.Ink)
                    Text("선생님, 생활지도 선생님처럼 가까이 있는 어른에게 찾아가서 말해 줘", style = hanaText(18.sp, FontWeight.Normal, 1.3f), color = RoadKit.InkSub)
                }
            }

            // 1388
            CallCard(
                number = "1388", title = "고민 상담 전화", sub = "고민을 들어 주는 곳이야",
                bg = KidHelp.CallBg, circle = Color.White, numberColor = KidHelp.CallTitle,
                titleColor = KidHelp.CallTitle, subColor = KidHelp.CallSub, seed = 7, outline = 2.dp,
            ) { dial("1388") }

            // 112
            CallCard(
                number = "112", title = "지금 위험할 때", sub = "누가 나를 다치게 하거나 무서울 때",
                bg = KidHelp.SosBg, circle = KidHelp.SosCircle, numberColor = Color.White,
                titleColor = KidHelp.SosTitle, subColor = KidHelp.SosSub, seed = 21, outline = 2.4.dp,
            ) { dial("112") }
        }

        // 맨 아래 안내
        Box(
            Modifier
                .padding(start = 18.dp, end = 18.dp, bottom = 28.dp)
                .fillMaxWidth()
                .dashedOutline(RoadKit.Road, 16.dp, 1.6.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text("앱이 어른에게 대신 알려 주지는 않아.\n꼭 직접 말하거나 전화해 줘.", style = hanaText(19.sp, FontWeight.Normal, 1.45f), color = RoadKit.Body)
        }
    }
}

@Composable
private fun CallCard(
    number: String,
    title: String,
    sub: String,
    bg: Color,
    circle: Color,
    numberColor: Color,
    titleColor: Color,
    subColor: Color,
    seed: Int,
    outline: Dp,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .handOutline(20.dp, seed = seed, width = outline)
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(start = 18.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(54.dp).clip(CircleShape).background(circle), contentAlignment = Alignment.Center) {
            Text(number, style = hanaText(if (number.length > 3) 20.sp else 22.sp, lineHeight = 1.1f), color = numberColor)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = hanaText(23.sp, lineHeight = 1.25f), color = titleColor)
            Text(sub, style = hanaText(18.sp, FontWeight.Normal, 1.3f), color = subColor)
        }
        Chevron(left = false, color = titleColor, size = 22.dp)
    }
}
