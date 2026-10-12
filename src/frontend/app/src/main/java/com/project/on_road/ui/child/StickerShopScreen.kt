package com.project.on_road.ui.child

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.project.on_road.data.AppContainer
import com.project.on_road.data.Sticker
import com.project.on_road.data.StickerStore
import com.project.on_road.ui.components.SpeakOnce
import com.project.on_road.ui.theme.OnRoadColors
import com.project.on_road.ui.theme.OnRoadType

/*
 * 스티커 가게 — 시안 D
 * 노란 간판(인사 + 가진 별) → 스티커 2칸 목록 → 맨 아래 안내
 * 카드: 살 수 있음(별 N개로 받기, 대표색) / 내 거야 / 별이 모자람(점선 카드)
 * 누르면 확인 창이 뜨고, 받으면 별이 줄고 내 스티커에 들어간다.
 */

/* ==================== 여기서 바꾸기 ==================== */
private object KidShop {
    val ItemBg = Color(0xFFFFF8E7)        // 스티커 칸 배경
    val LockedBg = Color(0xFFFAF7F2)      // 별이 모자란 카드
    val LockedDash = Color(0xFFD9CCBA)
}
/* ====================================================== */

/** 이름 + 아/야 */
private fun callName(name: String): String {
    val last = name.lastOrNull() ?: return "친구야"
    if (last !in '가'..'힣') return name
    return name + if ((last.code - 0xAC00) % 28 != 0) "아" else "야"
}

@Composable
fun StickerShopScreen(onBack: () -> Unit) {
    val session = AppContainer.session
    var balance by remember { mutableIntStateOf(session.starBalance) }
    var owned by remember { mutableStateOf(session.ownedStickers) }
    var picking by remember { mutableStateOf<Sticker?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    val name = session.nickname.ifBlank { "친구" }

    SpeakOnce("스티커 가게야. 모은 별로 스티커를 받아 봐!")

    Column(Modifier.fillMaxSize().background(RoadKit.Background)) {
        RoadSubHeader("스티커 가게", Tones.Yellow.bg, onBack = onBack, onHelp = { showHelp = true })

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            // 가게 간판
            item(span = { GridItemSpan(2) }) {
                Row(
                    Modifier
                        .padding(bottom = 4.dp)
                        .fillMaxWidth()
                        .handOutline(20.dp, seed = 7)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Tones.Yellow.bg)
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ImageSlot(Modifier.size(64.dp), Tones.Yellow.slot, radius = 16.dp, fill = Color.White.copy(alpha = 0.6f))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("어서 와, ${callName(name)}!", style = hanaText(22.sp, lineHeight = 1.3f), color = RoadKit.Ink)
                        Text(
                            buildAnnotatedString {
                                append("지금 가진 별 ")
                                withStyle(SpanStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold, color = RoadKit.Star)) { append("${balance}개") }
                            },
                            style = hanaText(18.sp, FontWeight.Normal, 1.3f),
                            color = RoadKit.InkSub,
                        )
                    }
                }
            }

            items(Sticker.entries) { s ->
                ShopItem(
                    sticker = s,
                    owned = s.name in owned,
                    affordable = balance >= s.price,
                    seed = if (s.ordinal % 2 == 0) 21 else 7,
                    onClick = { picking = s },
                )
            }

            item(span = { GridItemSpan(2) }) {
                Text(
                    "오늘의 미션을 하면 별을 받을 수 있어!",
                    style = hanaText(18.sp, FontWeight.Normal, 1.3f),
                    color = RoadKit.InkMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }

    picking?.let { s ->
        BuyDialog(
            sticker = s,
            owned = s.name in owned,
            balance = balance,
            onDismiss = { picking = null },
            onBuy = {
                if (StickerStore.buy(session, s)) {
                    balance = session.starBalance
                    owned = session.ownedStickers
                }
                picking = null
            },
        )
    }

    if (showHelp) {
        Dialog(onDismissRequest = { showHelp = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            ChildHelpScreen(onBack = { showHelp = false })
        }
    }
}

/** 다른 화면에서도 쓰는 별 표시 (그대로 둠) */
@Composable
fun StarBalance(count: Int, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(OnRoadColors.SurfaceMuted)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 9.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Star, null, tint = OnRoadColors.Star, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(4.dp))
        Text("$count", style = OnRoadType.Body1.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
    }
}

@Composable
private fun ShopItem(sticker: Sticker, owned: Boolean, affordable: Boolean, seed: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val locked = !owned && !affordable
    val base = if (locked) {
        Modifier.clip(shape).background(Color.White).dashedOutline(KidShop.LockedDash, 18.dp, 1.6.dp)
    } else {
        Modifier.handOutline(18.dp, seed).clip(shape).background(Color.White)
    }
    Column(
        base.clickable(onClick = onClick).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (locked) KidShop.LockedBg else KidShop.ItemBg),
            contentAlignment = Alignment.Center,
        ) { Text(sticker.emoji, fontSize = 46.sp) }
        Spacer(Modifier.height(8.dp))
        Text(
            "${sticker.label} 스티커",
            style = hanaText(20.sp, lineHeight = 1.25f),
            color = if (locked) RoadKit.InkMuted else RoadKit.Ink,
            maxLines = 1,
        )
        Spacer(Modifier.height(8.dp))
        val (label, bg, fg) = when {
            owned -> Triple("내 거야", RoadKit.Chip, RoadKit.InkSub)
            affordable -> Triple("별 ${sticker.price}개로 받기", RoadKit.Accent, Color.White)
            else -> Triple("별 ${sticker.price}개가 필요해", KidShop.LockedBg, RoadKit.InkMuted)
        }
        Box(
            Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(21.dp)).background(bg),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = hanaText(if (locked) 18.sp else 19.sp, if (locked) FontWeight.Normal else FontWeight.Bold, 1.2f), color = fg, maxLines = 1)
        }
    }
}

@Composable
private fun BuyDialog(sticker: Sticker, owned: Boolean, balance: Int, onDismiss: () -> Unit, onBuy: () -> Unit) {
    val enough = balance >= sticker.price
    val message = when {
        owned -> "이미 가지고 있는 스티커야!"
        enough -> "별 ${sticker.price}개로\n${sticker.label} 스티커를 받을까?"
        else -> "별이 ${sticker.price - balance}개 더 필요해.\n미션을 하고 다시 와 줘!"
    }
    SpeakOnce(message.replace("\n", " "))
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(4.dp)   // 손그림 선이 창 끝에 잘리지 않게
                .handOutline(24.dp, seed = 21)
                .clip(RoundedCornerShape(24.dp))
                .background(RoadKit.Background)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(96.dp).handOutline(48.dp, seed = 7).clip(CircleShape).background(Tones.Yellow.bg),
                contentAlignment = Alignment.Center,
            ) { Text(sticker.emoji, fontSize = 52.sp) }
            Spacer(Modifier.height(16.dp))
            Text(message, style = hanaText(23.sp, lineHeight = 1.4f), color = RoadKit.Ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            if (!owned && enough) {
                Box(
                    Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(RoadKit.Accent).clickable(onClick = onBuy),
                    contentAlignment = Alignment.Center,
                ) { Text("받을래!", style = hanaText(22.sp, lineHeight = 1.2f), color = Color.White) }
                Spacer(Modifier.height(8.dp))
            }
            Box(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(RoadKit.Chip).clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) { Text(if (!owned && enough) "다음에" else "알겠어", style = hanaText(20.sp, lineHeight = 1.2f), color = RoadKit.InkSub) }
        }
    }
}