package com.mightyfrog.stratagemcaller.data

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.Gson

object CatalogSeeder {
    suspend fun seed(context: Context, database: StratagemDatabase) {
        val dao = database.dao()
        val json = context.assets.open("stratagems/catalog.json").bufferedReader().use { it.readText() }
        val entries = Gson().fromJson(json, Array<Stratagem>::class.java).toList()
        require(entries.isNotEmpty()) { "Bundled stratagem catalog is empty." }
        database.withTransaction {
            dao.clear()
            dao.insertAll(entries)
        }
    }
}
