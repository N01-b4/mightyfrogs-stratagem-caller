package com.mightyfrog.stratagemcaller.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StratagemDao {
    @Query("SELECT * FROM stratagems ORDER BY name")
    fun observeAll(): Flow<List<Stratagem>>

    @Query("SELECT * FROM stratagems WHERE personalSelectable = 1 ORDER BY name")
    suspend fun personalCatalog(): List<Stratagem>

    @Query("SELECT * FROM stratagems WHERE missionSelectable = 1 ORDER BY name")
    suspend fun missionCatalog(): List<Stratagem>

    @Query("SELECT COUNT(*) FROM stratagems")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<Stratagem>)
}
