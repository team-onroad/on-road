package com.project.on_road.data

import java.time.LocalDate

/**
 * 백엔드 API 계약. 지금은 FakeOnRoadApi가 구현하고,
 * 백엔드가 준비되면 Retrofit 기반 구현체로 교체한다.
 */
interface OnRoadApi {
    suspend fun createUser(
        name: String,
        birthDate: LocalDate,
        region: String,
        status: LivingStatus
    ): UserProfile

    /** 없는 사용자면 null (앱은 온보딩으로) */
    suspend fun getUser(userId: String): UserProfile?

    suspend fun getProgress(userId: String): UserProgress

    suspend fun searchPolicies(userId: String, question: String): SearchResult
    suspend fun getPolicy(policyId: String): Policy?
    suspend fun getPolicies(ids: List<String>): List<Policy>

    suspend fun savePolicy(userId: String, policyId: String, saved: Boolean)
    suspend fun getSavedPolicyIds(userId: String): Set<String>

    suspend fun getChecklists(userId: String): List<Checklist>
    suspend fun getChecklist(userId: String, policyId: String): Checklist?
    suspend fun createChecklist(userId: String, policyId: String): Checklist
    suspend fun saveChecklist(userId: String, checklist: Checklist): Checklist

    suspend fun simulate(userId: String, income: Int, allocation: Map<BudgetCategory, Int>): SimulationResult

    suspend fun getNextActions(userId: String): List<NextAction>
    suspend fun getGrowth(userId: String): GrowthRecord
}
