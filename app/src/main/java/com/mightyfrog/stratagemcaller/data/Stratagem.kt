package com.mightyfrog.stratagemcaller.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stratagems")
data class Stratagem(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,
    val subcategory: String,
    val directions: String,
    val description: String,
    val unlock: String,
    val personalSelectable: Boolean,
    val missionSelectable: Boolean,
    val source: String,
    val verification: String
) {
    val directionList: List<Char>
        get() = directions.map { it.uppercaseChar() }.filter { it in "UDLR" }
}
