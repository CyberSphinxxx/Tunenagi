package com.tunenagi.core.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.json.JSONObject
import org.robolectric.RuntimeEnvironment

/**
 * Recreates a database exactly as an older version of the app created it — owned test code.
 *
 * This is the piece that makes a migration test honest. `SKILLS/migration.md` requires the test to
 * start from the *actual* older schema, so the statements come from the schema JSON Room exported
 * when that version was current, and they are Room's own DDL (`createSql`, with Room's
 * `${TABLE_NAME}` placeholder substituted) rather than hand-copied SQL. A future 2 -> 3 test calls
 * [createFromExported] with version 2 and gets the same guarantee.
 *
 * If an export ever lacks `createSql`, this fails loudly instead of creating a database that only
 * looks like the old one — a migration test that starts from the wrong schema is worse than none.
 */
internal object ExportedSchema {

    private const val DATABASE_CLASS = "com.tunenagi.core.database.TunenagiDatabase"

    /** The schema JSON for [version], read from the test assets (`core/schemas`). */
    fun json(version: Int, context: Context = RuntimeEnvironment.getApplication()): JSONObject {
        val path = "$DATABASE_CLASS/$version.json"
        val text = context.assets.open(path).bufferedReader().use { it.readText() }
        return JSONObject(text)
    }

    fun version(version: Int): Int = json(version).getJSONObject("database").getInt("version")

    fun tableNames(version: Int): List<String> {
        val entities = json(version).getJSONObject("database").getJSONArray("entities")
        return (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }
    }

    /** Every statement that version created, in the order Room would have run them. */
    fun createStatements(version: Int): List<String> {
        val entities = json(version).getJSONObject("database").getJSONArray("entities")
        val statements = mutableListOf<String>()
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            val table = entity.getString("tableName")
            statements += resolve(entity, table, "createSql")
            val indices = entity.optJSONArray("indices")
            if (indices == null) continue
            for (position in 0 until indices.length()) {
                val indexObject = indices.getJSONObject(position)
                statements += resolve(indexObject, table, "createSql")
            }
        }
        return statements
    }

    /**
     * Creates a real database file at [name] with [version]'s schema, runs [seed] against it and
     * closes it — leaving behind the "old installation" that a migration has to cope with.
     */
    fun createFromExported(name: String, version: Int, seed: (SupportSQLiteDatabase) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(name)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    createStatements(version).forEach(db::execSQL)
                    seed(db)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val db = helper.writableDatabase
        // `user_version` is what Room reads to choose migrations; the callback sets it on creation
        // and this states it explicitly in the test's own terms.
        db.execSQL("PRAGMA user_version = $version")
        db.close()
        helper.close()
    }

    /** Reads rows from a database file without opening it through Room. */
    fun <T> read(name: String, version: Int, sql: String, read: (android.database.Cursor) -> T): T {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val db = helper.writableDatabase
        return try {
            db.query(sql).use(read)
        } finally {
            db.close()
            helper.close()
        }
    }

    private fun resolve(container: JSONObject, table: String, key: String): String {
        val sql = container.optString(key).takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException(
                "The exported schema for `$table` has no $key; the migration test cannot rebuild the old database"
            )
        return sql.replace("\${TABLE_NAME}", table)
    }
}
