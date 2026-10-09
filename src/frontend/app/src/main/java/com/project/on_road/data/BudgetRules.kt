package com.project.on_road.data

object BudgetRules {
    val recommended = mapOf(
        BudgetCategory.HOUSING to 35,
        BudgetCategory.FOOD to 30,
        BudgetCategory.TRANSPORT to 8,
        BudgetCategory.PHONE to 5,
        BudgetCategory.ETC to 22
    )

    /** 비율(%)대로 나누고, 나누어떨어지지 않는 나머지는 기타에 더한다. */
    fun allocate(income: Int, ratios: Map<BudgetCategory, Int>): Map<BudgetCategory, Int> {
        val base = BudgetCategory.entries.associateWith { income * (ratios[it] ?: 0) / 100 }.toMutableMap()
        val rest = income - base.values.sum()
        base[BudgetCategory.ETC] = (base[BudgetCategory.ETC] ?: 0) + rest
        return base
    }
}
