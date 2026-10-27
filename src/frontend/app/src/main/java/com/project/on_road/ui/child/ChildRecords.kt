package com.project.on_road.ui.child

import com.project.on_road.data.ActivityStore

/*
 * 아동 기록을 휴대폰 안 저장소(ActivityStore)와 이어 주는 곳.
 * - 내 스티커 > 내 기록 탭에 보여 줄 기록 목록
 * - 좋아하는 거 찾기 결과 저장 (좋아하는 것 도감 · 청소년 흥미 탐색으로 이어짐)
 */

/** 내 기록 탭에 넘길 기록 (미션 · 상담 · 좋아하는 거 찾기 · 새 스티커) */
fun kidRecords(): List<KidRecord> = ActivityStore.kidLog().mapNotNull { e ->
    val kind = runCatching { KidRecordKind.valueOf(e.kind) }.getOrNull() ?: return@mapNotNull null
    KidRecord(e.date, kind, e.title, e.sub)
}

/** 좋아하는 거 찾기 '다 했어' */
fun saveKidFind(result: ChildFindResult) {
    if (result.activities.isEmpty()) return
    val labels = result.activities.map { it.label }
    val jobs = result.activities.flatMap { it.jobs }.distinct()
    ActivityStore.addLiked(labels, jobs, result.reasons)
    ActivityStore.logKid(
        KidRecordKind.FIND.name,
        labels.joinToString(", ") + " 좋아!",
        "해 볼 수 있는 일 · " + jobs.take(3).joinToString(", "),
    )
}
