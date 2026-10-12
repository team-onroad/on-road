package com.project.on_road.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Work
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.project.on_road.data.BudgetCategory
import com.project.on_road.ui.theme.OnRoadColors

/** 생활비 항목 색 (차트용 보조색) */
val BudgetCategory.color: Color
    get() = when (this) {
        BudgetCategory.HOUSING -> OnRoadColors.Primary
        BudgetCategory.FOOD -> OnRoadColors.ChartSand
        BudgetCategory.TRANSPORT -> OnRoadColors.ChartSky
        BudgetCategory.PHONE -> OnRoadColors.ChartClay
        BudgetCategory.ETC -> OnRoadColors.ChartSage
    }

val BudgetCategory.icon: ImageVector
    get() = when (this) {
        BudgetCategory.HOUSING -> Icons.Outlined.Apartment
        BudgetCategory.FOOD -> Icons.Outlined.Restaurant
        BudgetCategory.TRANSPORT -> Icons.Outlined.DirectionsBus
        BudgetCategory.PHONE -> Icons.Outlined.Smartphone
        BudgetCategory.ETC -> Icons.Outlined.Category
    }

fun categoryIcon(category: String): ImageVector = when (category) {
    "주거" -> Icons.Outlined.Apartment
    "생활·금융" -> Icons.Outlined.Savings
    "건강" -> Icons.Outlined.LocalHospital
    "취업" -> Icons.Outlined.Work
    "상담" -> Icons.Outlined.Forum
    "교육" -> Icons.Outlined.School
    "자립" -> Icons.Outlined.Flag
    else -> Icons.Outlined.Category
}
