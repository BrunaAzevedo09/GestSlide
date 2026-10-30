package com.google.mediapipe.examples.handlandmarker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Uma linha do histórico, já pronta para o PDF
data class Registo(
    val momento: Long,
    val modo: String,
    val comando: String,
    val destino: String,
    val sucesso: Boolean
)

// Base de dados local: guarda cada comando enviado (data/hora, modo, gesto, destino, resultado)
class LogDb(contexto: Context) : SQLiteOpenHelper(contexto, "gestslide_log.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE registos (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "momento INTEGER NOT NULL," +
                    "modo TEXT NOT NULL," +
                    "comando TEXT NOT NULL," +
                    "destino TEXT NOT NULL," +
                    "sucesso INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, antiga: Int, nova: Int) {
        db.execSQL("DROP TABLE IF EXISTS registos")
        onCreate(db)
    }

    // Guarda uma linha no histórico
    fun registar(modo: String, comando: String, destino: String, sucesso: Boolean) {
        val valores = ContentValues().apply {
            put("momento", System.currentTimeMillis())
            put("modo", modo)
            put("comando", comando)
            put("destino", destino)
            put("sucesso", if (sucesso) 1 else 0)
        }
        writableDatabase.insert("registos", null, valores)
    }

    // Devolve as últimas linhas já escritas em texto (mais recente primeiro)
    fun ultimos(limite: Int = 50, modo: String? = null): List<String> {
        val formato = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
        val lista = mutableListOf<String>()
        val filtro = if (modo != null) "WHERE modo = ? " else ""
        val argumentos = if (modo != null) arrayOf(modo, limite.toString()) else arrayOf(limite.toString())
        readableDatabase.rawQuery(
            "SELECT momento, modo, comando, destino, sucesso FROM registos " + filtro + "ORDER BY id DESC LIMIT ?",
            argumentos
        ).use { c ->
            while (c.moveToNext()) {
                val hora = formato.format(Date(c.getLong(0)))
                val estado = if (c.getInt(4) == 1) "✔" else "✖"
                lista.add("$hora  $estado  ${c.getString(2)}  → ${c.getString(3)}")
            }
        }
        return lista
    }

    // Devolve os registos todos (mais recente primeiro), para o PDF
    fun todos(limite: Int = 1000, modo: String? = null): List<Registo> {
        val lista = mutableListOf<Registo>()
        val filtro = if (modo != null) "WHERE modo = ? " else ""
        val argumentos = if (modo != null) arrayOf(modo, limite.toString()) else arrayOf(limite.toString())
        readableDatabase.rawQuery(
            "SELECT momento, modo, comando, destino, sucesso FROM registos " + filtro + "ORDER BY id DESC LIMIT ?",
            argumentos
        ).use { c ->
            while (c.moveToNext()) {
                lista.add(
                    Registo(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4) == 1)
                )
            }
        }
        return lista
    }

    // Apaga o histórico: de um modo só, ou de tudo se não for indicado modo
    fun apagarTudo(modo: String? = null) {
        if (modo != null) {
            writableDatabase.delete("registos", "modo = ?", arrayOf(modo))
        } else {
            writableDatabase.delete("registos", null, null)
        }
    }
}