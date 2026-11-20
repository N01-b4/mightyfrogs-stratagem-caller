package com.mightyfrog.stratagemcaller.data

import android.content.Context
import com.google.gson.Gson

object CatalogSeeder {
    suspend fun seed(context: Context, database: StratagemDatabase) {
        val dao = database.dao()
        if (dao.count() > 0) return
        val json = context.assets.open("stratagems/catalog.json").bufferedReader().use { it.readText() }
        val entries = Gson().fromJson(json, Array<Stratagem>::class.java).toList()
        dao.insertAll(entries)
    }
}
