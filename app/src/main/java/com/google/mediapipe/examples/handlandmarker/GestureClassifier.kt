package com.google.mediapipe.examples.handlandmarker
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.sqrt

fun distanciaEntreDoisPontos(a: NormalizedLandmark, b: NormalizedLandmark): Double {
    val dx = a.x() - b.x()
    val dy = a.y() - b.y()
    return sqrt((dx * dx + dy * dy).toDouble())
}

// Referência usada para normalizar (não muda com o gesto, só com o tamanho/distância da mão)
private fun calcularReferencia(landmarks: List<NormalizedLandmark>): Double {
    return distanciaEntreDoisPontos(landmarks[0], landmarks[9])
}

fun calcularRacioMedio(landmarks: List<NormalizedLandmark>): Double {
    val pulso = landmarks[0]
    val referencia = calcularReferencia(landmarks)
    if (referencia == 0.0) return 0.0

    val pontasDosDedos = listOf(
        landmarks[8],   // indicador
        landmarks[12],  // médio
        landmarks[16],  // anelar
        landmarks[20]   // mindinho
    )

    val distancias = pontasDosDedos.map { ponta ->
        distanciaEntreDoisPontos(pulso, ponta)
    }

    return distancias.average() / referencia
}

fun isPunhoFechado(landmarks: List<NormalizedLandmark>): Boolean {
    val racio = calcularRacioMedio(landmarks)
    // calibrado com valores reais: fechado ~0.73, aberto ~1.85
    return racio < 1.1
}

fun isPalmaAberta(landmarks: List<NormalizedLandmark>): Boolean {
    val racio = calcularRacioMedio(landmarks)
    // calibrado com valores reais: fechado ~0.73, aberto ~1.85
    return racio > 1.5
}

// Racio individual de um dedo (ponta em relação ao pulso, normalizado pelo tamanho da mão)
private fun racioDedo(landmarks: List<NormalizedLandmark>, indicePonta: Int): Double {
    val referencia = calcularReferencia(landmarks)
    if (referencia == 0.0) return 0.0
    return distanciaEntreDoisPontos(landmarks[0], landmarks[indicePonta]) / referencia
}

private const val RACIO_DEDO_ESTICADO = 1.15  // um dedo conta como esticado acima disto
private const val RACIO_DEDO_FECHADO = 1.2    // um dedo conta como fechado abaixo disto

enum class Navegacao {
    AVANCAR, RECUAR, NENHUM
}

// Verifica se só o indicador está esticado (médio, anelar e mindinho fechados) → 1 dedo
fun isApenasIndicadorEsticado(landmarks: List<NormalizedLandmark>): Boolean {
    val racioIndicador = racioDedo(landmarks, 8)
    ultimoRacioIndicador = racioIndicador

    val racioMedio = racioDedo(landmarks, 12)
    val racioAnelar = racioDedo(landmarks, 16)
    val racioMindinho = racioDedo(landmarks, 20)

    val outrosFechados = listOf(racioMedio, racioAnelar, racioMindinho).average() < RACIO_DEDO_FECHADO

    return racioIndicador > RACIO_DEDO_ESTICADO && outrosFechados
}

// Verifica se indicador E médio estão esticados, com anelar e mindinho fechados → 2 dedos
fun isIndicadorEMedioEsticados(landmarks: List<NormalizedLandmark>): Boolean {
    val racioIndicador = racioDedo(landmarks, 8)
    val racioMedio = racioDedo(landmarks, 12)
    ultimoRacioMedio = racioMedio

    val racioAnelar = racioDedo(landmarks, 16)
    val racioMindinho = racioDedo(landmarks, 20)

    val doisEsticados = racioIndicador > RACIO_DEDO_ESTICADO && racioMedio > RACIO_DEDO_ESTICADO
    val outrosFechados = listOf(racioAnelar, racioMindinho).average() < RACIO_DEDO_FECHADO

    return doisEsticados && outrosFechados
}

// Deteta 1 dedo (avançar) ou 2 dedos (recuar), com confirmação por vários frames seguidos
// para evitar falsos positivos durante a transição entre gestos
fun detetarNavegacao(landmarks: List<NormalizedLandmark>): Navegacao {
    val gestoBruto = when {
        isIndicadorEMedioEsticados(landmarks) -> Navegacao.RECUAR // verificar 2 dedos primeiro
        isApenasIndicadorEsticado(landmarks) -> Navegacao.AVANCAR
        else -> Navegacao.NENHUM
    }

    if (gestoBruto == ultimoGestoBruto && gestoBruto != Navegacao.NENHUM) {
        contadorConsecutivo++
    } else {
        contadorConsecutivo = 0
    }
    ultimoGestoBruto = gestoBruto

    return if (contadorConsecutivo >= FRAMES_PARA_CONFIRMAR) gestoBruto else Navegacao.NENHUM
}

// guarda os últimos rácios calculados, para mostrar no ecrã e calibrar
var ultimoRacioIndicador: Double = 0.0
var ultimoRacioMedio: Double = 0.0

// controlo da confirmação por vários frames, para filtrar ruído durante transições de gesto
private var contadorConsecutivo = 0
private var ultimoGestoBruto = Navegacao.NENHUM
private const val FRAMES_PARA_CONFIRMAR = 3

private const val LIMIAR_OK = 0.35 // distância normalizada entre polegar e indicador para contar como "a tocar"

var ultimaDistanciaOK: Double = 0.0

fun isSinalOK(landmarks: List<NormalizedLandmark>): Boolean {
    val referencia = calcularReferencia(landmarks)
    if (referencia == 0.0) return false

    val distanciaPolegarIndicador = distanciaEntreDoisPontos(landmarks[4], landmarks[8]) / referencia
    ultimaDistanciaOK = distanciaPolegarIndicador

    val racioMedio = racioDedo(landmarks, 12)
    val racioAnelar = racioDedo(landmarks, 16)
    val racioMindinho = racioDedo(landmarks, 20)
    val outrosEsticados = listOf(racioMedio, racioAnelar, racioMindinho).average() > RACIO_DEDO_ESTICADO

    return distanciaPolegarIndicador < LIMIAR_OK && outrosEsticados
}