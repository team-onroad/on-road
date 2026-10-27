package com.project.on_road.ui.screens

import android.content.Context
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.project.on_road.data.AppContainer
import com.project.on_road.data.remote.*
import kotlinx.coroutines.launch
import java.io.File

/** 대화 말풍선 */
sealed interface DocBubble {
    val id: Long

    data class Ai(
        override val id: Long,
        val text: String,
        val contacts: List<ContactDto> = emptyList(),
        val policies: List<String> = emptyList(),
        val problems: List<ProblemDto> = emptyList(),
        val exported: Boolean = false,
    ) : DocBubble

    data class Me(override val id: Long, val text: String) : DocBubble
}

enum class WriteMode { TALK, FIELDS }

/**
 * 문서 작성 화면 (docs/document-ai.md)
 * - 대화로 채우기: POST /documents/{id}/interview 로 빈 칸을 하나씩 묻고 채움, 다 채우면 서버가 바로 내보냄
 * - 칸별로 쓰기: 칸 카드 + 상단 AI 프롬프트(선택한 칸에 채움, POST assist) + 완료 버튼
 */
class DocWriterViewModel : ViewModel() {
    private val repo get() = AppContainer.documents

    var doc by mutableStateOf<DocumentDto?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var mode by mutableStateOf(WriteMode.TALK)

    // 칸별로 쓰기
    var selectedId by mutableStateOf<String?>(null)
    val drafts = mutableStateMapOf<String, String>()
    var prompt by mutableStateOf("")
    var notice by mutableStateOf<DocBubble.Ai?>(null) // 칸별 화면 위 안내 (정책 검색·고민·검사 결과)
        private set

    // 대화로 채우기
    val bubbles = mutableStateListOf<DocBubble>()
    var question by mutableStateOf<QuestionDto?>(null)
        private set
    var input by mutableStateOf("")
    private var talkStarted = false
    private var nextId = 1L

    // 내보내기
    var exported by mutableStateOf<ExportDto?>(null)
        private set
    var pdf by mutableStateOf<File?>(null)
        private set

    private var started = false

    fun start(documentId: Long?, policyId: Long?, templateId: String?, context: String?) {
        if (started) return
        started = true
        viewModelScope.launch {
            runCatching {
                when {
                    documentId != null -> repo.get(documentId)
                    policyId != null -> repo.startPolicyForm(policyId, context = context)
                    else -> repo.startTemplate(templateId.orEmpty(), context)
                }
            }.onSuccess { d ->
                doc = d
                selectedId = d.nextFieldId ?: d.fields.firstOrNull { it.writable }?.fieldId
                if (d.status == "exported") mode = WriteMode.FIELDS
                loading = false
                if (mode == WriteMode.TALK) startTalk()
            }.onFailure {
                error = message(it, "서류를 열 수 없어요. 잠시 후 다시 시도해 주세요.")
                loading = false
            }
        }
    }

    fun switchMode(m: WriteMode) {
        mode = m
        if (m == WriteMode.TALK) startTalk()
    }

    // ---------------- 대화로 채우기 ----------------

    private fun startTalk() {
        if (talkStarted || doc == null) return
        talkStarted = true
        talk(null)
    }

    fun send(text: String = input) {
        val msg = text.trim()
        if (msg.isEmpty() || busy) return
        input = ""
        bubbles += DocBubble.Me(nextId++, msg)
        talk(msg)
    }

    private fun talk(message: String?) {
        val d = doc ?: return
        busy = true
        viewModelScope.launch {
            runCatching { repo.interview(d.documentId, message) }
                .onSuccess { r ->
                    r.field?.let(::applyField)
                    applyProgress(r.progress)
                    val search = r.search
                    when {
                        r.route == "policy" && search != null ->
                            bubbles += DocBubble.Ai(nextId++, searchText(search), policies = search.results.map { it.name })
                        r.route == "crisis" -> bubbles += DocBubble.Ai(nextId++, r.message.orEmpty(), contacts = r.contacts)
                        r.check != null && !r.check.ready ->
                            bubbles += DocBubble.Ai(nextId++, r.message ?: "고칠 칸이 있어요.", problems = r.check.problems)
                        r.export != null -> {
                            bubbles += DocBubble.Ai(nextId++, r.message.orEmpty(), exported = true)
                            onExported(r.export)
                        }
                        !r.message.isNullOrBlank() -> bubbles += DocBubble.Ai(nextId++, r.message)
                    }
                    // 다음 질문. 같은 질문을 다시 묻는 경우(검색·안내·다시 입력)는 말풍선을 또 만들지 않는다
                    val q = r.question
                    if (q != null && (q.fieldId != question?.fieldId || q.text != question?.text)) {
                        bubbles += DocBubble.Ai(nextId++, q.text)
                        selectedId = q.fieldId
                    }
                    question = q
                }
                .onFailure { bubbles += DocBubble.Ai(nextId++, message(it, "답을 처리하지 못했어요. 다시 말해 주세요.")) }
            busy = false
        }
    }

    /** 지금 질문에 맞는 빠른 답 버튼 */
    fun quickReplies(): List<String> {
        val q = question ?: return emptyList()
        return buildList {
            if (q.awaitingConfirm) add(if (q.currentValue != null && q.inputType != "checkbox") "네" else "좋아")
            q.options?.let { addAll(it) }
            if (q.canSkip && !q.awaitingConfirm) add("해당 없음")
            add("나중에")
        }.distinct()
    }

    // ---------------- 칸별로 쓰기 ----------------

    fun field(id: String?): DocFieldDto? = doc?.fields?.firstOrNull { it.fieldId == id }

    fun text(f: DocFieldDto): String = drafts[f.fieldId] ?: f.value

    fun select(id: String) {
        selectedId = id
    }

    fun edit(f: DocFieldDto, value: String) {
        drafts[f.fieldId] = value
    }

    /** 체크박스: 하나만 고른다 (여러 개 고르는 양식은 다시 누르면 추가) */
    fun choose(f: DocFieldDto, option: String) {
        val now = text(f).split(", ").filter { it.isNotBlank() }
        drafts[f.fieldId] = if (option in now) (now - option).joinToString(", ") else option
    }

    fun complete(f: DocFieldDto) = fieldCall(f) {
        val value = drafts[f.fieldId]
        if (value != null && value != f.value) repo.saveValue(doc!!.documentId, f.fieldId, value, complete = true)
        else repo.complete(doc!!.documentId, f.fieldId, true)
    }

    fun reopen(f: DocFieldDto) = fieldCall(f) { repo.complete(doc!!.documentId, f.fieldId, false) }

    fun undo(f: DocFieldDto) = fieldCall(f) { repo.undo(doc!!.documentId, f.fieldId) }

    private fun fieldCall(f: DocFieldDto, block: suspend () -> FieldResponseDto) {
        if (busy || doc == null) return
        busy = true
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { r ->
                    drafts.remove(f.fieldId)
                    applyField(r.field)
                    applyProgress(r.progress)
                    if (r.field.isCompleted) r.nextFieldId?.let { selectedId = it }
                }
                .onFailure { notice = DocBubble.Ai(0, message(it, "저장하지 못했어요. 다시 시도해 주세요.")) }
            busy = false
        }
    }

    /** 상단 AI 프롬프트: 선택한 칸에 바로 채운다 (질문이면 라우터가 정책 검색·고민 안내로 보냄) */
    fun ask() {
        val f = field(selectedId) ?: return
        val p = prompt.trim()
        if (p.isEmpty() || busy) return
        busy = true
        viewModelScope.launch {
            // 직접 고치던 글이 있으면 먼저 저장해야 AI 가 그 글을 이어 쓴다
            runCatching {
                drafts[f.fieldId]?.takeIf { it != f.value }?.let { repo.saveValue(doc!!.documentId, f.fieldId, it) }
                repo.assist(doc!!.documentId, f.fieldId, p)
            }.onSuccess { r ->
                prompt = ""
                drafts.remove(f.fieldId)
                r.field?.let(::applyField)
                r.progress?.let(::applyProgress)
                notice = when (r.route) {
                    "policy" -> r.search?.let { DocBubble.Ai(0, searchText(it), policies = it.results.map { p -> p.name }) }
                    "counsel", "crisis" -> DocBubble.Ai(0, r.message.orEmpty(), contacts = r.contacts)
                    else -> null
                }
            }.onFailure { notice = DocBubble.Ai(0, message(it, "AI 도우미가 답하지 못했어요. 잠시 후 다시 시도해 주세요.")) }
            busy = false
        }
    }

    fun dismissNotice() {
        notice = null
    }

    /** 검사 → 통과하면 내보내기, 문제가 있으면 첫 문제 칸을 고른다 */
    fun export() {
        val d = doc ?: return
        if (busy) return
        busy = true
        viewModelScope.launch {
            runCatching {
                // 저장 안 한 글은 먼저 저장 (완료 처리는 사용자가)
                drafts.toMap().forEach { (id, v) -> if (v != field(id)?.value) applyField(repo.saveValue(d.documentId, id, v).field) }
                drafts.clear()
                val check = repo.check(d.documentId)
                if (!check.ready) {
                    notice = DocBubble.Ai(0, "내보내기 전에 고칠 칸이 있어요.", problems = check.problems)
                    check.problems.firstOrNull()?.let { selectedId = it.fieldId }
                    null
                } else repo.export(d.documentId)
            }.onSuccess { r -> r?.let(::onExported) }
                .onFailure { notice = DocBubble.Ai(0, message(it, "내보내지 못했어요. 잠시 후 다시 시도해 주세요.")) }
            busy = false
        }
    }

    // ---------------- 공통 ----------------

    private var appContext: Context? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    private fun onExported(r: ExportDto) {
        exported = r
        doc = r.document
        val ctx = appContext ?: return
        viewModelScope.launch {
            pdf = runCatching { repo.downloadPdf(ctx, r.document.documentId, r.document.title) }.getOrNull()
        }
    }

    fun redownload() {
        exported?.let(::onExported)
    }

    private fun applyField(f: DocFieldDto) {
        val d = doc ?: return
        doc = d.copy(fields = d.fields.map { if (it.fieldId == f.fieldId) f else it })
    }

    private fun applyProgress(p: DocProgressDto) {
        val d = doc ?: return
        doc = d.copy(progress = p)
    }

    private fun searchText(s: SearchResponseDto): String =
        s.answer?.takeIf { s.answerStatus == "answered" && it.isNotBlank() }
            ?: "확인할 수 있는 공식 정보가 없어요. 보건복지상담센터(129)에 확인해 보세요."

    private fun message(e: Throwable, fallback: String): String = (e as? ApiException)?.message?.takeIf { it.isNotBlank() } ?: fallback
}
