package com.google.mediapipe.examples.handlandmarker

import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI

class GestureSocketClient(serverUri: URI) : WebSocketClient(serverUri) {

    companion object {
        private const val TAG = "GestureSocketClient"
    }

    override fun onOpen(handshakedata: ServerHandshake?) {
        Log.d(TAG, "Ligado ao servidor: ${uri}")
    }

    override fun onMessage(message: String?) {
        // Não esperamos mensagens do PC, só enviamos — nada a fazer aqui
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        Log.d(TAG, "Ligação fechada: $reason")
    }

    override fun onError(ex: Exception?) {
        Log.e(TAG, "Erro na ligação WebSocket: ${ex?.message}")
    }

    fun enviarComando(comando: String) {
        if (isOpen) {
            send(comando)
        } else {
            Log.d(TAG, "Tentativa de enviar '$comando' mas o socket não está aberto")
        }
    }
}
