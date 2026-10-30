package com.google.mediapipe.examples.handlandmarker

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Cria o PDF do histórico de comandos (A4) e abre a janela de partilhar.
// Há um relatório para cada modo: "Apresentações" e "Casa Inteligente".
object PdfHistorico {

    private const val LARGURA = 595
    private const val ALTURA = 842
    private const val MARGEM = 36f
    private const val ALTURA_LINHA = 24f

    private const val ESCURO = 0xFF1E1E2E.toInt()
    private const val CINZA = 0xFF6B6B80.toInt()
    private const val BRANCO = 0xFFFFFFFF.toInt()
    private const val VERDE = 0xFF22C55E.toInt()
    private const val VERMELHO = 0xFFEF4444.toInt()

    // Cores e textos de cada relatório
    private class Tema(
        val principal: Int,
        val destaque: Int,
        val cabecalho: Int,
        val cartao: Int,
        val zebra: Int,
        val subtitulo: Int,
        val titulo: String,
        val descricao: String,
        val ficheiro: String
    )

    private val temaApresentacoes = Tema(
        principal = 0xFF4834D4.toInt(),
        destaque = 0xFF7C8CFF.toInt(),
        cabecalho = 0xFFE8EAFF.toInt(),
        cartao = 0xFFF3F4FF.toInt(),
        zebra = 0xFFF8F8FC.toInt(),
        subtitulo = 0xFFE0E4FF.toInt(),
        titulo = "Histórico – Apresentações",
        descricao = "Controlo de slides no PowerPoint e no Canva",
        ficheiro = "GestSlide_historico_apresentacoes.pdf"
    )

    private val temaCasa = Tema(
        principal = 0xFF15803D.toInt(),
        destaque = 0xFF4ADE80.toInt(),
        cabecalho = 0xFFE3F6EA.toInt(),
        cartao = 0xFFEFF9F2.toInt(),
        zebra = 0xFFF6FBF8.toInt(),
        subtitulo = 0xFFDDF7E6.toInt(),
        titulo = "Histórico – Casa Inteligente",
        descricao = "Luzes, estore e portão da maquete",
        ficheiro = "GestSlide_historico_casa.pdf"
    )

    // Nome de cada comando, sem a descrição do sinal
    private fun nomeGesto(comando: String): String = when (comando) {
        "AVANCAR" -> "Avançar"
        "RECUAR" -> "Recuar"
        "PAUSAR" -> "Pausar"
        "LUZ_ON" -> "Ligar luzes"
        "LUZ_OFF" -> "Desligar luzes"
        "ESTORE_SOBE" -> "Estore a subir"
        "ESTORE_DESCE" -> "Estore a descer"
        "PORTAO_TOGGLE" -> "Portão"
        else -> comando
    }

    private fun pincel(tamanho: Float, cor: Int, negrito: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = tamanho
            color = cor
            typeface = if (negrito) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

    // Corta o texto com "…" se não couber na largura
    private fun cortar(t: String, p: Paint, largura: Float): String {
        if (p.measureText(t) <= largura) return t
        var r = t
        while (r.isNotEmpty() && p.measureText("$r…") > largura) r = r.dropLast(1)
        return "$r…"
    }

    private fun escrever(c: Canvas, t: String, x: Float, topo: Float, altura: Float, p: Paint) {
        c.drawText(t, x, topo + altura / 2f + p.textSize / 3f, p)
    }

    // modo = "Apresentações" ou "Casa Inteligente"
    fun criar(ctx: Context, modo: String): File? {
        val tema = if (modo == "Casa Inteligente") temaCasa else temaApresentacoes
        val registos = LogDb(ctx).todos(1000, modo)
        if (registos.isEmpty()) return null

        val total = registos.size
        val enviados = registos.count { it.sucesso }
        val percentagem = enviados * 100 / total
        val porGesto = registos.groupBy { it.comando }
            .map { it.key to it.value.size }
            .sortedByDescending { it.second }
        val barras = porGesto.take(8)
        val maisUsado = porGesto.first()

        // Onde começa a tabela na 1.ª página e quantas linhas cabem
        val yBarras = 232f
        val yTitulo = yBarras + 22f * barras.size + 30f
        val yCabecalho1 = yTitulo + 30f
        val topoLinhas1 = yCabecalho1 + ALTURA_LINHA
        val limite = ALTURA - 50f
        val cabem1 = ((limite - topoLinhas1) / ALTURA_LINHA).toInt()
        val yCabecalhoN = 64f
        val topoLinhasN = yCabecalhoN + ALTURA_LINHA
        val cabemN = ((limite - topoLinhasN) / ALTURA_LINHA).toInt()
        val restantes = maxOf(0, total - cabem1)
        val totalPaginas = 1 + (restantes + cabemN - 1) / cabemN

        val dataFmt = SimpleDateFormat("dd/MM/yyyy", Locale("pt", "PT"))
        val horaFmt = SimpleDateFormat("HH:mm:ss", Locale("pt", "PT"))
        val geradoFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "PT"))

        val doc = PdfDocument()
        var indice = 0
        var pagina = 0

        while (pagina < totalPaginas) {
            pagina++
            val page = doc.startPage(PdfDocument.PageInfo.Builder(LARGURA, ALTURA, pagina).create())
            val c = page.canvas
            val fundo = Paint().apply { style = Paint.Style.FILL }

            val yCab: Float
            val topoLinhas: Float
            val cabem: Int

            if (pagina == 1) {
                // Faixa de cima
                fundo.color = tema.principal
                c.drawRect(0f, 0f, LARGURA.toFloat(), 96f, fundo)
                fundo.color = tema.destaque
                c.drawRect(0f, 96f, LARGURA.toFloat(), 100f, fundo)
                c.drawText("GestSlide", MARGEM, 48f, pincel(28f, BRANCO, true))
                c.drawText(tema.titulo, MARGEM, 72f, pincel(13f, tema.subtitulo, true))
                val dir = pincel(9f, tema.subtitulo).apply { textAlign = Paint.Align.RIGHT }
                c.drawText("Gerado em " + geradoFmt.format(Date()), LARGURA - MARGEM, 48f, dir)
                c.drawText(tema.descricao, LARGURA - MARGEM, 72f, dir)

                // Três cartões de resumo
                val gap = 12f
                val larg = (LARGURA - 2 * MARGEM - 2 * gap) / 3f
                val cartoes = listOf(
                    "Comandos registados" to total.toString(),
                    "Enviados com ligação" to "$enviados ($percentagem %)",
                    "Gesto mais usado" to nomeGesto(maisUsado.first)
                )
                for ((i, par) in cartoes.withIndex()) {
                    val x = MARGEM + i * (larg + gap)
                    fundo.color = tema.cartao
                    c.drawRoundRect(RectF(x, 124f, x + larg, 188f), 10f, 10f, fundo)
                    c.drawText(par.first, x + 12f, 146f, pincel(9f, CINZA))
                    val pv = pincel(if (i == 2) 14f else 18f, ESCURO, true)
                    c.drawText(cortar(par.second, pv, larg - 24f), x + 12f, 174f, pv)
                }

                // Barras: comandos por gesto
                c.drawText("Comandos por gesto", MARGEM, 214f, pincel(12f, tema.principal, true))
                val maximo = barras.first().second.toFloat()
                val pLabel = pincel(9.5f, ESCURO)
                val pNum = pincel(9.5f, CINZA, true)
                for ((i, par) in barras.withIndex()) {
                    val topo = yBarras + i * 22f
                    escrever(c, nomeGesto(par.first), MARGEM, topo, 18f, pLabel)
                    val largBarra = 240f * par.second / maximo
                    fundo.color = tema.destaque
                    c.drawRoundRect(
                        RectF(MARGEM + 110f, topo + 3f, MARGEM + 110f + maxOf(largBarra, 4f), topo + 15f),
                        4f, 4f, fundo
                    )
                    escrever(c, par.second.toString(), MARGEM + 110f + largBarra + 8f, topo, 18f, pNum)
                }

                // Título da tabela
                c.drawText("Registos", MARGEM, yTitulo, pincel(12f, tema.principal, true))
                c.drawText(
                    "«Enviado» = a ligação estava aberta quando o comando foi enviado.",
                    MARGEM, yTitulo + 14f, pincel(8f, CINZA)
                )
                yCab = yCabecalho1
                topoLinhas = topoLinhas1
                cabem = cabem1
            } else {
                c.drawText(tema.titulo + " (continuação)", MARGEM, 44f, pincel(12f, tema.principal, true))
                yCab = yCabecalhoN
                topoLinhas = topoLinhasN
                cabem = cabemN
            }

            // Cabeçalho da tabela
            fundo.color = tema.cabecalho
            c.drawRect(MARGEM, yCab, LARGURA - MARGEM, yCab + ALTURA_LINHA, fundo)
            val pCab = pincel(9f, tema.principal, true)
            val colunas = floatArrayOf(MARGEM + 8f, MARGEM + 96f, MARGEM + 186f, MARGEM + 380f)
            val titulos = listOf("Data", "Hora", "Gesto", "Estado")
            for (i in titulos.indices) escrever(c, titulos[i], colunas[i], yCab, ALTURA_LINHA, pCab)

            // Linhas
            val pLinha = pincel(9.5f, ESCURO)
            var n = 0
            while (n < cabem && indice < total) {
                val r = registos[indice]
                val topo = topoLinhas + n * ALTURA_LINHA
                if (n % 2 == 1) {
                    fundo.color = tema.zebra
                    c.drawRect(MARGEM, topo, LARGURA - MARGEM, topo + ALTURA_LINHA, fundo)
                }
                val quando = Date(r.momento)
                escrever(c, dataFmt.format(quando), colunas[0], topo, ALTURA_LINHA, pLinha)
                escrever(c, horaFmt.format(quando), colunas[1], topo, ALTURA_LINHA, pLinha)
                escrever(c, cortar(nomeGesto(r.comando), pLinha, 180f), colunas[2], topo, ALTURA_LINHA, pLinha)
                fundo.color = if (r.sucesso) VERDE else VERMELHO
                c.drawCircle(colunas[3] + 4f, topo + ALTURA_LINHA / 2f, 3.5f, fundo)
                escrever(c, if (r.sucesso) "Enviado" else "Sem ligação", colunas[3] + 14f, topo, ALTURA_LINHA, pLinha)
                indice++
                n++
            }

            // Rodapé
            fundo.color = tema.cabecalho
            c.drawRect(MARGEM, ALTURA - 36f, LARGURA - MARGEM, ALTURA - 35f, fundo)
            c.drawText(tema.titulo, MARGEM, ALTURA - 20f, pincel(8f, CINZA))
            val pPag = pincel(8f, CINZA).apply { textAlign = Paint.Align.RIGHT }
            c.drawText("Página $pagina de $totalPaginas", LARGURA - MARGEM, ALTURA - 20f, pPag)

            doc.finishPage(page)
        }

        val pasta = File(ctx.cacheDir, "pdf").apply { mkdirs() }
        val ficheiro = File(pasta, tema.ficheiro)
        FileOutputStream(ficheiro).use { doc.writeTo(it) }
        doc.close()
        return ficheiro
    }

    // Abre a janela "Partilhar" (guardar no Drive, enviar por e-mail, abrir, etc.)
    fun partilhar(ctx: Context, ficheiro: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", ficheiro)
        val envio = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(envio, "Histórico em PDF"))
    }
}