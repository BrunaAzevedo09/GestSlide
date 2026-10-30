package com.google.mediapipe.examples.handlandmarker.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import com.google.mediapipe.examples.handlandmarker.R

class TutorialFragment : Fragment() {

    private data class Gesto(
        val emoji: String,
        val etiqueta: String,
        val titulo: String,
        val descricao: String
    )

    private val gestosApresentacao = listOf(
        Gesto("☝️", "AVANÇAR", "1 dedo — Avançar", "Levanta só o dedo indicador"),
        Gesto("✌️", "RECUAR", "2 dedos — Recuar", "Levanta o indicador e o médio"),
        Gesto("👌", "PAUSAR", "Sinal de OK — Pausar", "Junta o polegar e o indicador")
    )

    private val gestosCasa = listOf(
        Gesto("🖐", "LUZES LIGADAS", "Mão aberta — Ligar luzes", "Mostra a palma com os dedos abertos"),
        Gesto("✊", "LUZES DESLIGADAS", "Punho — Desligar luzes", "Fecha a mão"),
        Gesto("☝️", "ESTORE SOBE", "1 dedo — Subir o estore", "Levanta só o dedo indicador"),
        Gesto("✌️", "ESTORE DESCE", "2 dedos — Descer o estore", "Levanta o indicador e o médio"),
        Gesto("👌", "PORTÃO", "Sinal de OK — Abrir ou fechar o portão", "Junta o polegar e o indicador")
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_tutorial, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences("config_app", android.content.Context.MODE_PRIVATE)

        val painelMenu = view.findViewById<View>(R.id.painel_menu)
        val painelTutorial = view.findViewById<View>(R.id.painel_tutorial)
        val lista = view.findViewById<LinearLayout>(R.id.lista_gestos)
        val checkbox = view.findViewById<CheckBox>(R.id.checkbox_nao_mostrar)
        val botaoComecar = view.findViewById<Button>(R.id.botao_comecar)

        var modoAtual = "APRESENTACAO"

        fun irParaPermissoes() {
            Navigation.findNavController(view).navigate(R.id.action_tutorial_to_permissions)
        }

        fun abrirModo(casa: Boolean) {
            modoAtual = if (casa) "ESP32" else "APRESENTACAO" // nomes internos, o utilizador não os vê
            prefs.edit().putString("modo_alvo", modoAtual).apply()

            // Se já viu o tutorial deste modo e pediu para não voltar a ver, avança logo
            if (prefs.getBoolean("tutorial_visto_$modoAtual", false)) {
                irParaPermissoes()
                return
            }

            view.findViewById<android.widget.ImageView>(R.id.tutorial_emoji).setImageResource(
                if (casa) R.drawable.ic_modo_casa else R.drawable.ic_modo_apresentacao
            )
            view.findViewById<TextView>(R.id.tutorial_titulo).text =
                if (casa) "Casa Inteligente" else "Apresentações"
            view.findViewById<TextView>(R.id.tutorial_subtitulo).text =
                if (casa) "Controla as luzes, o estore e o portão sem tocar em nada"
                else "Controla a tua apresentação sem tocar em nada"

            lista.removeAllViews()
            for (g in if (casa) gestosCasa else gestosApresentacao) {
                val item = layoutInflater.inflate(R.layout.item_gesto, lista, false)
                item.findViewById<TextView>(R.id.gesto_emoji).text = g.emoji
                item.findViewById<TextView>(R.id.gesto_etiqueta).text = g.etiqueta
                item.findViewById<TextView>(R.id.gesto_titulo).text = g.titulo
                item.findViewById<TextView>(R.id.gesto_descricao).text = g.descricao
                lista.addView(item)
            }

            checkbox.isChecked = false
            painelMenu.visibility = View.GONE
            painelTutorial.visibility = View.VISIBLE
        }

        view.findViewById<View>(R.id.menu_apresentacao).setOnClickListener { abrirModo(false) }
        view.findViewById<View>(R.id.menu_casa).setOnClickListener { abrirModo(true) }

        view.findViewById<View>(R.id.botao_voltar_menu).setOnClickListener {
            painelTutorial.visibility = View.GONE
            painelMenu.visibility = View.VISIBLE
        }

        botaoComecar.setOnClickListener {
            if (checkbox.isChecked) {
                prefs.edit().putBoolean("tutorial_visto_$modoAtual", true).apply()
            }
            irParaPermissoes()
        }
    }
}