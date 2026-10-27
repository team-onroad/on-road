package com.project.on_road.data

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/*
 * 올린 양식 파일(hwpx · pdf)에서 '작성할 항목'을 찾아 FormDraft 로 만든다.
 *
 * - hwpx: 압축(zip) 안의 Contents/section*.xml 을 읽어 표(행·칸)와 문단을 꺼낸다. 라이브러리 필요 없음.
 * - pdf : pdfbox-android 로 글자를 꺼낸다. build.gradle 에 아래 한 줄이 있어야 한다.
 *           implementation("com.tom-roush:pdfbox-android:2.0.27.0")
 *         (리플렉션으로 불러서, 라이브러리가 없어도 앱 빌드는 된다. 그때는 PDF만 '읽을 수 없어요'가 뜬다)
 * - hwp(예전 한글 파일)·사진: 지금은 읽지 못한다고 안내한다.
 *
 * 항목 찾는 규칙 (AI 없이 규칙으로):
 *  1) 표에서 '짧은 글자 칸' 바로 오른쪽 칸이 비어 있으면 → 그 글자가 항목 제목
 *  2) 표 머리줄(모두 짧은 글자) 아래 줄이 비어 있으면 → 머리줄 칸마다 항목
 *  3) 번호(1. 가. ①)·네모(□ ■)로 시작하거나 ':'로 끝나는 짧은 줄 → 항목 제목
 *  4) 제목 바로 뒤의 ※ 줄 → 그 항목의 주의할 점
 * 항목 제목으로 쉬운 이름·쓰는 법·예시를 붙인다 (아래 Guides). 모르는 항목은 기본 안내를 쓴다.
 */
object FormParser {

    /** 읽지 못했을 때 화면에 그대로 보여 줄 문장을 담는다 */
    class ReadError(message: String) : Exception(message)

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private enum class Kind { PDF, HWPX, HWP, IMAGE, OTHER }

    private fun kindOf(bytes: ByteArray, mime: String): Kind {
        fun startsWith(vararg b: Int) = bytes.size >= b.size && b.indices.all { (bytes[it].toInt() and 0xFF) == b[it] }
        return when {
            startsWith(0x25, 0x50, 0x44, 0x46) -> Kind.PDF                       // %PDF
            startsWith(0x50, 0x4B) -> Kind.HWPX                                 // zip (hwpx)
            startsWith(0xD0, 0xCF, 0x11, 0xE0) -> Kind.HWP                       // 예전 hwp
            mime.startsWith("image/") -> Kind.IMAGE
            else -> Kind.OTHER
        }
    }

    /** 실패하면 ReadError (화면에 그대로 보여 줄 문장) */
    fun parse(bytes: ByteArray, mime: String): FormDraft {
        val blocks = when (kindOf(bytes, mime)) {
            Kind.HWPX -> hwpxBlocks(bytes) ?: throw ReadError("한글(hwpx) 파일을 열지 못했어요. 파일이 손상되지 않았는지 확인해 주세요.")
            Kind.PDF -> pdfBlocks(bytes)
            Kind.HWP -> throw ReadError("예전 한글 파일(.hwp)은 아직 못 읽어요. 한글에서 '다른 이름으로 저장 → HWPX'로 바꿔 올려 주세요.")
            Kind.IMAGE -> throw ReadError("사진은 아직 못 읽어요. PDF나 한글(hwpx) 파일로 올려 주세요.")
            Kind.OTHER -> throw ReadError("PDF나 한글(hwpx) 파일만 올릴 수 있어요.")
        }
        val fields = findFields(blocks)
        if (fields.isEmpty()) throw ReadError("작성할 항목을 찾지 못했어요. 표나 번호로 나뉜 양식이면 더 잘 읽어요.")
        return FormDraft(templateId = null, title = findTitle(blocks), fields = fields, originalExport = false)
    }

    // ───────────── 문서 → 블록 (문단 / 표의 한 줄) ─────────────

    private sealed interface Block
    private data class Para(val text: String) : Block
    private data class Row(val cells: List<String>) : Block

    /** hwpx: section*.xml 을 순서대로 읽는다 */
    private fun hwpxBlocks(bytes: ByteArray): List<Block>? = runCatching {
        val sections = sortedMapOf<Int, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val m = Regex("""Contents/section(\d+)\.xml""").matchEntire(e.name)
                if (m != null) sections[m.groupValues[1].toInt()] = zip.readBytes()
                e = zip.nextEntry
            }
        }
        sections.values.flatMap { sectionBlocks(it) }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun sectionBlocks(xml: ByteArray): List<Block> {
        val out = mutableListOf<Block>()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(ByteArrayInputStream(xml), "UTF-8")
        var tableDepth = 0
        var row: MutableList<String>? = null
        var cell: StringBuilder? = null
        var para: StringBuilder? = null
        var inText = false
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = p.name?.substringAfter(':')
            when (event) {
                XmlPullParser.START_TAG -> when (name) {
                    "tbl" -> { tableDepth++ }
                    "tr" -> { if (tableDepth == 1) row = mutableListOf() }
                    "tc" -> { if (tableDepth == 1) cell = StringBuilder() }
                    "p" -> {
                        if (tableDepth == 0) para = StringBuilder()
                        else cell?.let { if (it.isNotEmpty()) it.append(' ') }
                    }
                    "t" -> { inText = true }
                    "tab" -> { (cell ?: para)?.append(' ') }
                }
                XmlPullParser.TEXT -> { if (inText) (cell ?: para)?.append(p.text) }
                XmlPullParser.END_TAG -> when (name) {
                    "t" -> { inText = false }
                    "p" -> if (tableDepth == 0) {
                        para?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += Para(it) }
                        para = null
                    }
                    "tc" -> if (tableDepth == 1) {
                        row?.add(cell?.toString()?.trim().orEmpty())
                        cell = null
                    }
                    "tr" -> if (tableDepth == 1) {
                        row?.let { out += Row(it) }
                        row = null
                    }
                    "tbl" -> { tableDepth = (tableDepth - 1).coerceAtLeast(0) }
                }
            }
            event = p.next()
        }
        return out
    }

    /** pdf: pdfbox-android 로 글자를 꺼내 줄 단위 블록으로. 줄 안에 칸 간격(공백 2개 이상)이 있으면 표의 한 줄로 본다 */
    private fun pdfBlocks(bytes: ByteArray): List<Block> {
        val text = pdfText(bytes)
            ?: throw ReadError("PDF를 읽으려면 앱에 PDF 읽기 기능(pdfbox-android)을 추가해야 해요.")
        if (text.isBlank()) throw ReadError("글자를 꺼낼 수 없는 PDF예요(스캔본일 수 있어요). 한글(hwpx) 파일이 있으면 그걸 올려 주세요.")
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            val cells = line.split(Regex("""\s{2,}|\t""")).map { it.trim() }
            if (cells.size >= 2) Row(cells) else Para(line)
        }
    }

    /** pdfbox-android 를 리플렉션으로 부른다 (없으면 null) */
    private fun pdfText(bytes: ByteArray): String? {
        val ctx = appContext ?: return null
        return runCatching {
            Class.forName("com.tom_roush.pdfbox.android.PDFBoxResourceLoader")
                .getMethod("init", Context::class.java).invoke(null, ctx)
            val docClass = Class.forName("com.tom_roush.pdfbox.pdmodel.PDDocument")
            val doc = docClass.getMethod("load", ByteArray::class.java).invoke(null, bytes)
            try {
                val stripper = Class.forName("com.tom_roush.pdfbox.text.PDFTextStripper").getConstructor().newInstance()
                stripper.javaClass.getMethod("getText", docClass).invoke(stripper, doc) as String
            } finally {
                runCatching { docClass.getMethod("close").invoke(doc) }
            }
        }.getOrNull()
    }

    // ───────────── 항목 찾기 ─────────────

    private val NumberPrefix = Regex("""^(\(?\d{1,2}[.)]|[①-⑳]|[가-하][.)]|[□■○●◇◆▶▷•·\-])\s*""")
    private val Blankish = Regex("""^[\s_.\-·:년월일()\[\]/~원세]*$""")

    /** "성    명" → "성명" (한 글자씩 띄운 제목 붙이기), 번호·콜론 떼기 */
    private fun clean(raw: String): String {
        var t = raw.trim().replace(NumberPrefix, "").trim().trimEnd(':', '：').trim()
        val parts = t.split(Regex("""\s+"""))
        if (parts.size in 2..6 && parts.all { it.length == 1 }) t = parts.joinToString("")
        return t
    }

    /** 항목 제목처럼 보이는 짧은 글인지 */
    private fun looksLikeLabel(t: String): Boolean {
        if (t.length !in 2..24) return false
        if (t.startsWith("※")) return false
        if (t.endsWith("다.") || t.endsWith("요.") || t.endsWith("니다") || t.endsWith("함.")) return false
        if (Blankish.matches(t)) return false
        if (t.count { it.isDigit() } > 4) return false
        return t.any { it in '가'..'힣' || it.isLetter() }
    }

    private fun isBlankCell(t: String) = t.isBlank() || Blankish.matches(t)

    /** 쉬운 안내가 준비된 항목 이름인지 (성명, 주소, 연락처 …) */
    private fun knownLabel(t: String): Boolean {
        val key = t.replace(" ", "")
        return Guides.any { g -> g.keys.any { k -> key.contains(k.replace(" ", ""), ignoreCase = true) } }
    }

    private fun findFields(blocks: List<Block>): List<FormField> {
        val labels = mutableListOf<Pair<String, String?>>()   // 제목, 원래 글(괄호 속 안내용)
        val seen = mutableSetOf<String>()
        val cautions = mutableMapOf<String, String>()
        var last: String? = null

        fun add(raw: String) {
            val label = clean(raw)
            if (!looksLikeLabel(label)) return
            val key = label.replace(" ", "")
            if (!seen.add(key)) return
            labels += label to raw
            last = label
        }

        blocks.forEachIndexed { i, b ->
            when (b) {
                is Row -> {
                    val cells = b.cells
                    val next = blocks.getOrNull(i + 1) as? Row
                    val allLabels = cells.size >= 2 && cells.all { looksLikeLabel(clean(it)) }
                    if (allLabels && next != null && next.cells.all { isBlankCell(it) }) {
                        // 머리줄 + 빈 줄 → 머리줄 칸마다 항목
                        cells.forEach { add(it) }
                    } else {
                        cells.forEachIndexed { c, t ->
                            val right = cells.getOrNull(c + 1)
                            val below = next?.cells?.getOrNull(c)
                            val single = cells.size == 1 && below != null && isBlankCell(below)
                            if (t.isBlank()) return@forEachIndexed
                            when {
                                (right != null && isBlankCell(right)) || single -> add(t)
                                NumberPrefix.containsMatchIn(t.take(3)) -> add(t)
                                // PDF 는 빈칸이 사라지므로, 잘 알려진 항목 이름이면 그대로 항목으로
                                clean(t).length <= 10 && knownLabel(clean(t)) -> add(t)
                            }
                        }
                    }
                }
                is Para -> {
                    val t = b.text
                    when {
                        t.startsWith("※") -> last?.let { l -> if (l !in cautions) cautions[l] = t.removePrefix("※").trim().take(80) }
                        // "성명: ______" 처럼 제목 뒤에 빈칸이 있는 줄
                        t.contains(':') || t.contains('：') -> {
                            val head = t.split(':', '：').first()
                            val tail = t.substringAfter(':', t.substringAfter('：', ""))
                            if (isBlankCell(tail)) add(head)
                        }
                        NumberPrefix.containsMatchIn(t.take(3)) -> add(t)
                        t.length <= 12 && knownLabel(clean(t)) -> add(t)
                    }
                }
            }
        }

        return labels.take(40).mapIndexed { i, (label, raw) -> fieldOf(i, label, raw, cautions[label]) }
    }

    private fun findTitle(blocks: List<Block>): String {
        val paras = blocks.filterIsInstance<Para>().map { it.text.trim() }.filter { it.length in 3..40 }
        return paras.firstOrNull { p -> listOf("신청서", "지원서", "계획서", "확인서", "동의서", "보고서", "양식", "서식").any { p.replace(" ", "").contains(it) } }
            ?: paras.firstOrNull()
            ?: "올린 양식"
    }

    // ───────────── 항목별 쉬운 이름 · 쓰는 법 ─────────────

    private data class Guide(
        val keys: List<String>,
        val easy: String,
        val guide: String,
        val example: String? = null,
        val caution: String? = null,
        val multiline: Boolean = false,
        val outline: List<String>? = null,
    )

    private val Guides = listOf(
        Guide(listOf("주민등록번호", "주민번호"), "주민등록번호", "13자리를 정확히 써요.", caution = "다른 사람이 보지 않게 조심해요."),
        Guide(listOf("생년월일"), "태어난 날", "년·월·일 순서로 써요.", "2007. 03. 15."),
        Guide(listOf("성명", "이름", "신청인", "지원자"), "이름", "신분증에 적힌 이름 그대로 써요.", "홍길동"),
        Guide(listOf("주소", "거주지", "주거지", "소재지"), "사는 곳 주소", "주민등록상 주소를 도로명으로 써요.", "서울시 ○○구 ○○로 12, 101호"),
        Guide(listOf("연락처", "전화", "휴대폰", "핸드폰", "휴대전화"), "연락받을 번호", "연락이 잘 되는 휴대폰 번호를 써요.", "010-1234-5678"),
        Guide(listOf("이메일", "e-mail", "전자우편"), "이메일", "자주 확인하는 이메일을 써요.", "onroad@example.com"),
        Guide(listOf("성별"), "성별", "해당하는 것을 써요."),
        Guide(listOf("학교", "학력", "소속", "학년", "전공"), "다니는 곳", "학교·학과(또는 직장) 이름을 써요.", "○○고등학교 2학년"),
        Guide(listOf("작성일", "신청일", "제출일", "일자", "날짜"), "쓴 날짜", "제출하는 날짜를 써요.", "2026. 10. 16."),
        Guide(listOf("서명", "날인", "(인)"), "서명", "원본에는 직접 서명하거나 도장을 찍어요. 여기엔 이름만 적어 둬요."),
        Guide(listOf("계좌", "은행", "예금주"), "돈 받을 통장", "은행 이름과 계좌번호를 써요.", "○○은행 123-456-789012", caution = "본인 이름으로 된 통장인지 확인해요."),
        Guide(listOf("보호자", "법정대리인", "친권자", "후견인"), "보호자", "보호자(시설장 등)의 이름과 연락처를 써요."),
        Guide(listOf("가족", "가구원", "세대원"), "함께 사는 가족", "이름·관계·나이를 한 줄씩 써요.", multiline = true),
        Guide(listOf("소득", "급여", "월급", "수입", "금액"), "금액", "원 단위 숫자로 써요.", "1,500,000원"),
        Guide(listOf("자격증", "면허"), "가진 자격증", "자격증 이름과 딴 날짜를 써요.", "컴퓨터활용능력 2급 (2026. 5.)", multiline = true),
        Guide(listOf("경력", "활동", "봉사", "수상", "경험"), "해 본 일", "언제·어디서·무엇을 했는지 한 줄씩 써요.", multiline = true),
        Guide(listOf("동기", "사유", "이유", "필요성"), "신청하는 이유", "왜 필요한지 내 상황과 함께 써요.", multiline = true,
            outline = listOf("지금 나의 상황", "이 지원이 필요한 이유", "받게 되면 어떻게 쓸지")),
        Guide(listOf("자기소개", "성장과정", "장점", "단점", "성격"), "나를 소개하는 글", "나는 어떤 사람인지 경험을 들어 써요.", multiline = true,
            outline = listOf("나는 어떤 사람인지", "그렇게 생각하는 경험", "앞으로의 모습")),
        Guide(listOf("계획", "포부", "목표"), "앞으로의 계획", "언제까지 무엇을 할지 구체적으로 써요.", multiline = true,
            outline = listOf("이루고 싶은 목표", "구체적인 실천 계획", "기대하는 변화")),
        Guide(listOf("내용", "사항", "설명", "의견"), "적을 내용", "양식이 묻는 내용을 짧은 문장으로 써요.", multiline = true),
    )

    private fun fieldOf(i: Int, label: String, raw: String?, caution: String?): FormField {
        val key = label.replace(" ", "")
        val g = Guides.firstOrNull { it.keys.any { k -> key.contains(k.replace(" ", ""), ignoreCase = true) } }
        // 괄호 속 조건 (예: "(500자 이내)") 은 쓰는 법에 붙인다
        val hint = Regex("""\(([^)]{2,20})\)""").find(raw ?: label)?.groupValues?.get(1)
        val base = g?.guide ?: "양식의 이 칸에 들어갈 내용을 써요. 모르면 담당 기관에 물어봐요."
        return FormField(
            id = "f$i",
            label = label,
            easyLabel = g?.easy ?: label,
            guide = if (hint != null && hint !in base) "$base ($hint)" else base,
            caution = caution ?: g?.caution,
            example = g?.example,
            multiline = g?.multiline ?: false,
            essay = g?.outline != null,
            outline = g?.outline,
        )
    }
}
