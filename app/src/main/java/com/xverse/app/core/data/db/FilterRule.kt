package com.xverse.app.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 过滤规则类型 */
enum class RuleType(val label: String) {
    CSS("CSS"),      // 隐藏选择器，如 article[data-testid="tweet"]:has(...)
    REGEX("REGEX"),  // 匹配推文文本/用户名的正则
    STORE("STORE")   // Redux 拦截：__typename / advertiserAccount 判定
}

/**
 * 过滤规则实体。
 * 来源：builtin（内置）、remote（远程规则库）、user（用户自定义）。
 */
@Entity(
    tableName = "filter_rules",
    indices = [Index("enabled"), Index("source")],
)
data class FilterRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: RuleType,
    val pattern: String,
    val enabled: Boolean = true,
    val builtin: Boolean = false,
    val source: String = "user",
    val version: String = "",
    val description: String = "",
)
