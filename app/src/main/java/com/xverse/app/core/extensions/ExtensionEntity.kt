package com.xverse.app.core.extensions

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 已安装扩展（来自 Chrome/Edge 扩展商店的 .crx 导入，或 .user.js 用户脚本）。
 *
 * 主键 [id]：包/脚本内容 SHA-256 前 16 字节的 hex（32 字符），同一扩展重装即覆盖。
 * 扩展文件解压在 filesDir/extensions/<id>/；本表只存元数据。
 *
 * 内容脚本 / 权限以 JSON 数组序列化存储（Room 无复杂集合字段，org.json 序列化最简）。
 */
@Entity(
    tableName = "extensions",
    indices = [Index("enabled")],
)
data class ExtensionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val version: String,
    /** manifest_version：2=MV2，3=MV3 */
    val manifestVersion: Int,
    val description: String = "",
    val enabled: Boolean = true,
    /** 来源：USERSCRIPT=油猴脚本，CHROME=Chrome 扩展商店，EDGE=Edge 扩展商店 */
    val source: String = "CHROME",
    /** 配置页相对路径（如 options/options.html），无则空串 */
    val optionsPage: String = "",
    /** 已复制到扩展目录内的图标文件名（如 icon.png），无则空串 */
    val iconPath: String = "",
    /** content_scripts 数组 JSON（[{matches,js,css,runAt,allFrames}]） */
    val contentScriptsJson: String = "[]",
    /** permissions 数组 JSON */
    val permissionsJson: String = "[]",
    val homepageUrl: String = "",
    val author: String = "",
    val installedAt: Long = 0,
)
