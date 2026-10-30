package com.google.mediapipe.examples.handlandmarker

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Contas (Firebase Authentication) e dados na nuvem (Firestore)
object Nuvem {

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    private val bd: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    // Sem google-services.json o Firebase não é inicializado: nesse caso não há sessão
    fun estaLigado(): Boolean = try { auth.currentUser != null } catch (e: Exception) { false }
    fun emailAtual(): String? = try { auth.currentUser?.email } catch (e: Exception) { null }

    // aoTerminar recebe null se correu bem, ou o texto do erro
    fun entrar(email: String, palavra: String, aoTerminar: (String?) -> Unit) {
        auth.signInWithEmailAndPassword(email, palavra)
            .addOnSuccessListener { aoTerminar(null) }
            .addOnFailureListener { aoTerminar(traduzirErro(it)) }
    }

    fun criarConta(email: String, palavra: String, aoTerminar: (String?) -> Unit) {
        auth.createUserWithEmailAndPassword(email, palavra)
            .addOnSuccessListener { aoTerminar(null) }
            .addOnFailureListener { aoTerminar(traduzirErro(it)) }
    }

    fun sair() {
        auth.signOut()
    }

    private fun traduzirErro(e: Exception): String = when (e) {
        is FirebaseAuthWeakPasswordException -> "A palavra-passe tem de ter pelo menos 6 caracteres."
        is FirebaseAuthUserCollisionException -> "Já existe uma conta com este e-mail."
        is FirebaseAuthInvalidUserException -> "Não existe conta com este e-mail."
        is FirebaseAuthInvalidCredentialsException -> "E-mail ou palavra-passe incorretos."
        else -> "Erro: ${e.localizedMessage ?: "verifica a internet"}"
    }

    private fun docUtilizador() =
        auth.currentUser?.uid?.let { bd.collection("utilizadores").document(it) }

    // Guarda na conta os IPs do PC e da casa
    fun guardarIps(ipPc: String, ipCasa: String) {
        val doc = docUtilizador() ?: return
        doc.set(mapOf("ip_pc" to ipPc, "ip_casa" to ipCasa), SetOptions.merge())
    }

    // Lê da conta os IPs guardados (null se não houver)
    fun carregarIps(aoTerminar: (String?, String?) -> Unit) {
        val doc = docUtilizador()
        if (doc == null) {
            aoTerminar(null, null)
            return
        }
        doc.get()
            .addOnSuccessListener { d -> aoTerminar(d.getString("ip_pc"), d.getString("ip_casa")) }
            .addOnFailureListener { aoTerminar(null, null) }
    }

    // Guarda uma linha de histórico na nuvem
    fun registarLog(modo: String, comando: String, destino: String, sucesso: Boolean) {
        val doc = docUtilizador() ?: return
        doc.collection("logs").add(
            mapOf(
                "momento" to System.currentTimeMillis(),
                "modo" to modo,
                "comando" to comando,
                "destino" to destino,
                "sucesso" to sucesso
            )
        )
    }

    // Últimas linhas do histórico da conta, já em texto (null se falhar)
    fun ultimosLogs(limite: Int, aoTerminar: (List<String>?) -> Unit) {
        val doc = docUtilizador()
        if (doc == null) {
            aoTerminar(null)
            return
        }
        val formato = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
        doc.collection("logs")
            .orderBy("momento", Query.Direction.DESCENDING)
            .limit(limite.toLong())
            .get()
            .addOnSuccessListener { r ->
                val linhas = r.documents.map { d ->
                    val hora = formato.format(Date(d.getLong("momento") ?: 0L))
                    val estado = if (d.getBoolean("sucesso") == true) "✔" else "✖"
                    "$hora  $estado  ${d.getString("comando")}  → ${d.getString("destino")}"
                }
                aoTerminar(linhas)
            }
            .addOnFailureListener { aoTerminar(null) }
    }
}