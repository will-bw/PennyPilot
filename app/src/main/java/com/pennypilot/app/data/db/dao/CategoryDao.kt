package com.pennypilot.app.data.db.dao

import androidx.room.*
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.domain.model.TxType
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Insert
    suspend fun insert(cat: CategoryEntity): Long

    @Insert
    suspend fun insertAll(cats: List<CategoryEntity>)

    @Update
    suspend fun update(cat: CategoryEntity)

    @Query("SELECT * FROM categories WHERE enabled = 1 ORDER BY sort_order")
    fun observeEnabled(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sort_order")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE type = :type AND enabled = 1 ORDER BY sort_order")
    fun observeByType(type: TxType): Flow<List<CategoryEntity>>

    @Query("SELECT COALESCE(MAX(color_index), -1) FROM categories")
    suspend fun maxColorIndex(): Int

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?
}
