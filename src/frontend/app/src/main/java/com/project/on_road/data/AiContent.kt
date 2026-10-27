package com.project.on_road.data

// ───────────── 쉬운 말 단계 (api.md 3.23) ─────────────

data class EasyLevel(val code: String, val name: String)

data class EasyTerm(val term: String, val gloss: String)

data class EasyTextView(
    val level: String,
    val levelName: String,
    /** 나이 기준 추천 단계 */
    val recommendedLevel: String,
    /** 고를 수 있는 단계 (L1→L7) */
    val levels: List<EasyLevel>,
    val coreText: String,
    /** 더 알아보기. 없으면 null */
    val moreText: String?,
    val terms: List<EasyTerm>,
    /** false 면 사람이 원문과 대조하기 전 → 안내 문구 표시 */
    val verified: Boolean,
)

// ───────────── 신청서 작성 도우미 (api.md 3.24) ─────────────

enum class FieldSource { PROFILE, USER, HANDWRITE, OFFICE }

data class FormGuideItem(
    val fieldId: String,
    val label: String,
    val section: String?,
    /** "앱 정보로 미리 채움 (맞는지 확인)" 같은 작성 방법 */
    val how: String,
    val source: FieldSource,
    val required: Boolean?,
    val guide: String,
    val example: String?,
    val prefill: String?,
    /** 근거가 된 양식 원문 */
    val evidence: String?,
)

data class FormGuideView(
    val title: String,
    val checked: Boolean,
    val notes: List<String>,
    val items: List<FormGuideItem>,
    /** 항상 화면에 표시 */
    val notices: List<String>,
)
