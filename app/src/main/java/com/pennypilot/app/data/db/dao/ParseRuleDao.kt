package com.pennypilot.app.data.db.dao

import androidx.room.*
import com.pennypilot.app.data.db.entity.ParseRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ParseRuleDao {
    @Query("SELECT * FROM parse_rules WHERE app_package = :pkg AND enabled = 1 ORDER BY priority ASC")
    suspend fun getRulesForPackage(pkg: String): List<ParseRuleEntity>

    @Query("SELECT * FROM parse_rules ORDER BY priority ASC")
    fun observeAll(): Flow<List<ParseRuleEntity>>

    @Insert
    suspend fun insertAll(rules: List<ParseRuleEntity>)

    @Insert
    suspend fun insert(rule: ParseRuleEntity): Long

    @Update
    suspend fun update(rule: ParseRuleEntity)

    @Delete
    suspend fun delete(rule: ParseRuleEntity)

    @Query("DELETE FROM parse_rules")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM parse_rules")
    suspend fun count(): Int
}
