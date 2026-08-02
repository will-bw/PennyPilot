package com.pennypilot.app.data.db.entity

import androidx.room.*
import com.pennypilot.app.domain.model.TxType

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "icon") val icon: String,
    @ColumnInfo(name = "color_index") val colorIndex: Int,
    @ColumnInfo(name = "type") val type: TxType,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "enabled") val enabled: Boolean = true,
    @ColumnInfo(name = "is_preset") val isPreset: Boolean = false
)
