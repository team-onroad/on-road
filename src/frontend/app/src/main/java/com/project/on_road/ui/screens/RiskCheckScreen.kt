package com.project.on_road.ui.screens

import androidx.compose.foundation.layout.Box

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.on_road.data.AppContainer
import com.project.on_road.data.FormUpload
import com.project.on_road.data.remote.RiskCheckDto
import com.project.on_road.data.remote.RiskContextDto
import com.project.on_road.data.remote.RiskFindingDto
import com.project.on_road.data.remote.userMessage
import com.project.on_road.ui.components.*
import com.project.on_road.ui.theme.*
import kotlinx.coroutines.launch

/*
 * 서류 속 숨겨진 위험 찾기 (POST /documents/risk-check)
 * 서류 고르기 → (등기부등본이면 계약 정보) → 사진·PDF 올리기 → 결과 (위험 · 확인해 보세요 · 괜찮아요)
 * 규칙은 서버 doc_risk_rules.json. 파일과 결과는 저장하지 않는다.
 */

enum class RiskDoc(val code: String, val title: String, val sub: String) {
    LEASE("lease", "월세·전세 계약서", "보증금 계좌, 전입신고 막는 특약, 수리 책임"),
    REGISTRY("registry", "등기부등본", "진짜 주인인지, 빚이 얼마나 있는지"),
    LABOR("labor", "근로계약서", "최저임금, 주휴수당, 그만두면 물어내라는 조항"),
}

class RiskCheckViewModel : ViewModel() {
    var doc by mutableStateOf<RiskDoc?>(null)
        private set
    var result by mutableStateOf<RiskCheckDto?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    // 등기부등본: 계약서와 맞춰 볼 정보 (모두 비워도 된다)
    var landlord by mutableStateOf("")
    var address by mutableStateOf("")
    var deposit by mutableStateOf("") // 만 원
    var price by mutableStateOf("") // 만 원

    fun pick(d: RiskDoc) {
        doc = d
        error = null
    }

    fun upload(context: Context, uri: Uri) {
        val d = doc ?: return
        loading = true
        error = null
        viewModelScope.launch {
            runCatching {
                val file = FormUpload.prepare(context, uri)
                if (file.isHwp) throw FormUpload.NotSupported()
                AppContainer.documents.riskCheck(file, d.code, contextFor(d))
            }.onSuccess { result = it }
                .onFailure {
                    error = when (it) {
                        is FormUpload.TooBig, is FormUpload.NotSupported -> it.message
                        else -> it.userMessage("서류를 읽지 못했어요. 글자가 잘 보이게 밝은 곳에서 다시 찍어 주세요.")
                    }
                }
            loading = false
        }
    }

    private fun contextFor(d: RiskDoc): RiskContextDto? {
        if (d != RiskDoc.REGISTRY) return null
        fun won(s: String) = s.filter(Char::isDigit).toLongOrNull()?.times(10_000)
        val c = RiskContextDto(
            landlordName = landlord.trim().ifBlank { null },
            address = address.trim().ifBlank { null },
            deposit = won(deposit),
            marketPrice = won(price),
        )
        return if (c == RiskContextDto()) null else c
    }

    /** 뒤로: 결과 → 올리기 → 서류 고르기. 더 못 가면 false */
    fun stepBack(): Boolean = when {
        result != null -> { result = null; true }
        doc != null -> { doc = null; error = null; true }
        else -> false
    }

    fun again() {
        result = null
        error = null
    }
}

@Composable
fun RiskCheckScreen(
    onBack: () -> Unit,
    onPractice: (String) -> Unit,
    onNavigate: (String) -> Unit = {},
    vm: RiskCheckViewModel = viewModel(),
) {
    val context = LocalContext.current
    val uploader = rememberFormUploader { uri -> vm.upload(context, uri) }
    val back = { if (!vm.stepBack()) onBack() }
    BackHandler(onBack = back)

    Column(Modifier.fillMaxSize().background(OnRoadColors.Background)) {
        OnRoadTopBar("서류 위험 찾기", back)
        val d = vm.doc
        val r = vm.result
        // 서류 고르기 → 올리기 → 읽는 중 → 결과, 단계가 바뀔 때마다 부드럽게 넘어간다
        val page = when {
            vm.loading -> RiskPage.LOADING
            r != null -> RiskPage.RESULT
            d == null -> RiskPage.PICK
            else -> RiskPage.UPLOAD
        }
        FadeSwitch(page, Modifier.weight(1f), label = "risk-page") { p ->
            when (p) {
                RiskPage.LOADING -> LoadingState(
                    "서류를 읽고 있어요",
                    steps = listOf("조항을 하나씩 살펴보고 있어요", "위험한 부분을 정리하고 있어요"),
                )
                RiskPage.RESULT -> vm.result?.let {
                    ResultPage(it, onPractice, onAgain = vm::again) {
                        NextTaskBlock(onNavigate, finished = com.project.on_road.data.PlanGo.RiskCheck)
                    }
                }
                RiskPage.PICK -> PickPage(vm::pick)
                RiskPage.UPLOAD -> vm.doc?.let { UploadPage(it, vm, uploader) }
            }
        }
    }
}

private enum class RiskPage { LOADING, RESULT, PICK, UPLOAD }

/* ---------------------------- 서류 고르기 ---------------------------- */

@Composable
private fun PickPage(onPick: (RiskDoc) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OnRoadDimens.ScreenPadding),
    ) {
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RiskDoc.entries.forEachIndexed { si, d -> Box(Modifier.stagger(si)) { RowCard(d.title, d.sub) { onPick(d) } } }
        }
    }
}

/* ---------------------------- 올리기 ---------------------------- */

@Composable
private fun UploadPage(d: RiskDoc, vm: RiskCheckViewModel, uploader: FormUploader) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OnRoadDimens.ScreenPadding),
        ) {
            Spacer(Modifier.height(24.dp))
            Text(d.title, style = OnRoadType.Title1, color = OnRoadColors.TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(d.sub, style = OnRoadType.Caption, color = OnRoadColors.TextTertiary)

            if (d == RiskDoc.REGISTRY) {
                Spacer(Modifier.height(OnRoadDimens.BlockGap))
                SubHeader("계약서와 맞춰 볼게요")
                Spacer(Modifier.height(4.dp))
                Text("비워 둬도 돼요. 넣으면 더 정확하게 봐요.", style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LabeledField("계약서의 집주인 이름", vm.landlord, { vm.landlord = it.take(40) }, "예: 김철수")
                    LabeledField("계약할 집 주소", vm.address, { vm.address = it.take(200) }, "동·호수까지")
                    LabeledField("보증금", vm.deposit, { vm.deposit = it.filter(Char::isDigit).take(7) }, "예: 1000", suffix = "만 원", number = true)
                    LabeledField("집값(시세)", vm.price, { vm.price = it.filter(Char::isDigit).take(7) }, "예: 15000", suffix = "만 원", number = true)
                }
                Spacer(Modifier.height(6.dp))
                Text("집값은 국토교통부 실거래가 공개시스템에서 볼 수 있어요.", style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
            }

            vm.error?.let {
                Spacer(Modifier.height(16.dp))
                NoticeBox(it, title = "읽지 못했어요", tone = NoticeTone.Warning)
            }
            Spacer(Modifier.height(24.dp))
        }
        Column(
            Modifier
                .background(OnRoadColors.Background)
                .padding(horizontal = OnRoadDimens.ScreenPadding, vertical = 16.dp),
        ) {
            Text("글자가 잘 보이게, 모든 쪽을 올려 주세요.", style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
            Spacer(Modifier.height(10.dp))
            UploadButtons(uploader.takePhoto, uploader.pickFile)
        }
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    suffix: String? = null,
    number: Boolean = false,
) {
    Column {
        Text(label, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextSecondary)
        Spacer(Modifier.height(6.dp))
        OnRoadTextField(
            value = value,
            onValueChange = onChange,
            placeholder = placeholder,
            keyboardType = if (number) KeyboardType.Number else KeyboardType.Text,
            suffix = suffix,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/* ---------------------------- 결과 ---------------------------- */

@Composable
private fun ResultPage(r: RiskCheckDto, onPractice: (String) -> Unit, onAgain: () -> Unit, next: @Composable () -> Unit = {}) {
    val danger = r.findings.filter { it.status == "danger" }
    val check = r.findings.filter { it.status == "check" || it.status == "unknown" }
    val ok = r.findings.filter { it.status == "ok" }
    var showOk by remember(r) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OnRoadDimens.ScreenPadding),
        ) {
            Spacer(Modifier.height(24.dp))
            Text(r.label, style = OnRoadType.Caption.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextTertiary)
            Spacer(Modifier.height(6.dp))
            Text(r.summary.headline, style = OnRoadType.Title1, color = OnRoadColors.TextPrimary)

            if (danger.isNotEmpty()) {
                Group("계약 전에 꼭 해결해요") { danger.forEachIndexed { si, it -> Box(Modifier.stagger(si)) { FindingCard(it, onPractice) } } }
            }
            if (check.isNotEmpty()) {
                Group("물어보거나 더 확인해요") { check.forEachIndexed { si, it -> Box(Modifier.stagger(si)) { FindingCard(it, onPractice) } } }
            }
            if (ok.isNotEmpty()) {
                Spacer(Modifier.height(OnRoadDimens.BlockGap))
                Row(
                    Modifier.fillMaxWidth().clip(OnRoadShapes.Item).tap { showOk = !showOk }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("괜찮은 부분", style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
                    Text(if (showOk) "접기" else "보기", style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Medium), color = OnRoadColors.TextTertiary)
                }
                ExpandArea(showOk) {
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ok.forEach { f -> RowCard(f.title, f.explain, onClick = null) }
                    }
                }
            }

            Spacer(Modifier.height(OnRoadDimens.BlockGap))
            next()
            Spacer(Modifier.height(24.dp))
            Text(r.notice, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary)
            Spacer(Modifier.height(24.dp))
        }
        Column(
            Modifier
                .background(OnRoadColors.Background)
                .padding(horizontal = OnRoadDimens.ScreenPadding, vertical = 16.dp),
        ) {
            OnRoadButton("다른 서류도 보기", onAgain, Modifier.fillMaxWidth(), variant = ButtonVariant.Outline, borderColor = OnRoadColors.BorderChip)
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(OnRoadDimens.BlockGap))
    SubHeader(title)
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** 연회색 카드: 제목 · 상태 태그 / 서류에 적힌 말 / 쉬운 설명 / 이렇게 해요 / 연습 */
@Composable
private fun FindingCard(f: RiskFindingDto, onPractice: (String) -> Unit) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        background = OnRoadColors.SurfaceSubtle,
        borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.title, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            // 위험: 메인 색 바탕 + 흰 글씨 / 확인: 흰 바탕 + 회색 테두리
            OnRoadTag(
                when (f.status) { "danger" -> "위험"; "unknown" -> "못 찾음"; else -> "확인" },
                style = if (f.status == "danger") TagStyle.PrimaryFilled else TagStyle.Neutral,
                textStyle = OnRoadType.Caption2.copy(fontWeight = if (f.status == "danger") FontWeight.Bold else FontWeight.Medium),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        f.evidence?.let { ev ->
            Spacer(Modifier.height(10.dp))
            // 서류에 적힌 그대로
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(OnRoadShapes.Inner)
                    .background(OnRoadColors.Surface)
                    .border(1.dp, OnRoadColors.BorderChip, OnRoadShapes.Inner)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text("서류에 적힌 말", style = OnRoadType.Micro, color = OnRoadColors.TextTertiary)
                Spacer(Modifier.height(2.dp))
                Text("“$ev”", style = OnRoadType.Caption, color = OnRoadColors.TextPrimary)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(f.explain, style = OnRoadType.Caption, color = OnRoadColors.TextSecondary)
        f.action?.let {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Top) {
                Text("이렇게 해요", style = OnRoadType.Caption2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary, modifier = Modifier.width(64.dp).padding(top = 1.dp))
                Text(it, style = OnRoadType.Caption, color = OnRoadColors.TextPrimary, modifier = Modifier.weight(1f))
            }
        }
        f.practice?.let { p ->
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PrimaryPill("${p.title} 연습", { onPractice(p.scenarioId) })
            }
        }
    }
}

/** 연회색 줄 카드: 제목 · 설명 (· ›) */
@Composable
private fun RowCard(title: String, sub: String, onClick: (() -> Unit)?) {
    OnRoadCard(
        Modifier.fillMaxWidth(),
        background = OnRoadColors.SurfaceSubtle,
        borderColor = OnRoadColors.Border.copy(alpha = 0.7f),
        shape = OnRoadShapes.Item,
        contentPadding = PaddingValues(start = 16.dp, end = if (onClick != null) 10.dp else 16.dp, top = 16.dp, bottom = 16.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = OnRoadType.Body2.copy(fontWeight = FontWeight.Bold), color = OnRoadColors.TextPrimary)
                Spacer(Modifier.height(2.dp))
                Text(sub, style = OnRoadType.Caption2, color = OnRoadColors.TextTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (onClick != null) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = OnRoadColors.TextQuaternary, modifier = Modifier.size(20.dp))
            }
        }
    }
}
