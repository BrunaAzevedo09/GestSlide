/*

 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.mediapipe.examples.handlandmarker.fragment
import com.google.mediapipe.examples.handlandmarker.Nuvem
import com.google.mediapipe.examples.handlandmarker.LogDb
import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import java.net.URI
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Toast
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Camera
import androidx.camera.core.AspectRatio
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.Navigation
import com.google.mediapipe.examples.handlandmarker.GestureSocketClient
import com.google.mediapipe.examples.handlandmarker.HandLandmarkerHelper
import com.google.mediapipe.examples.handlandmarker.MainViewModel
import com.google.mediapipe.examples.handlandmarker.R
import com.google.mediapipe.examples.handlandmarker.databinding.FragmentCameraBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraFragment : Fragment(), HandLandmarkerHelper.LandmarkerListener {

    companion object {
        private const val TAG = "Hand Landmarker"
    }

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding
        get() = _fragmentCameraBinding!!

    private lateinit var handLandmarkerHelper: HandLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT
    private lateinit var gestureSocketClient: GestureSocketClient
    private var ultimaDirecaoEnviada: String = "NENHUM"

    // Confirmação por manter o gesto
    private var gestoPendente: String? = null
    private var inicioPendente: Long = 0L
    private var direcaoAnterior: String = "NENHUM"
    private val tempoConfirmarMs = 2000L

    /** Blocking ML operations are performed using this executor */
    private lateinit var backgroundExecutor: ExecutorService

    override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(
                requireActivity(), R.id.fragment_container
            ).navigate(R.id.action_camera_to_permissions)
        }

        // Start the HandLandmarkerHelper again when users come back
        // to the foreground.
        backgroundExecutor.execute {
            if (handLandmarkerHelper.isClose()) {
                handLandmarkerHelper.setupHandLandmarker()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if(this::handLandmarkerHelper.isInitialized) {
            viewModel.setMaxHands(handLandmarkerHelper.maxNumHands)
            viewModel.setMinHandDetectionConfidence(handLandmarkerHelper.minHandDetectionConfidence)
            viewModel.setMinHandTrackingConfidence(handLandmarkerHelper.minHandTrackingConfidence)
            viewModel.setMinHandPresenceConfidence(handLandmarkerHelper.minHandPresenceConfidence)
            viewModel.setDelegate(handLandmarkerHelper.currentDelegate)

            // Close the HandLandmarkerHelper and release resources
            backgroundExecutor.execute { handLandmarkerHelper.clearHandLandmarker() }
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()

        // Shut down our background executor
        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(
            Long.MAX_VALUE, TimeUnit.NANOSECONDS
        )

        // Fecha a ligação WebSocket ao PC e liberta o gerador de som
        if (this::gestureSocketClient.isInitialized) {
            gestureSocketClient.close()
        }
        toneGenerator?.release()
        toneGenerator = null
        toneGeneratorPausar?.release()
        toneGeneratorPausar = null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding =
            FragmentCameraBinding.inflate(inflater, container, false)

        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize our background executor
        backgroundExecutor = Executors.newSingleThreadExecutor()

        // Wait for the views to be properly laid out
        fragmentCameraBinding.viewFinder.post {
            // Set up the camera and its use cases
            setUpCamera()
        }

        // Create the HandLandmarkerHelper that will handle the inference
        backgroundExecutor.execute {
            handLandmarkerHelper = HandLandmarkerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                minHandDetectionConfidence = viewModel.currentMinHandDetectionConfidence,
                minHandTrackingConfidence = viewModel.currentMinHandTrackingConfidence,
                minHandPresenceConfidence = viewModel.currentMinHandPresenceConfidence,
                maxNumHands = viewModel.currentMaxHands,
                currentDelegate = viewModel.currentDelegate,
                handLandmarkerHelperListener = this
            )
        }

        // Attach listeners to UI control widgets
        initBottomSheetControls()

        // Pergunta o IP do PC em vez de estar fixo no código
        mostrarDialogoIp()

        // Toque no título "GestSlide" no topo para mudar o IP, se mudares de PC
        requireActivity().findViewById<android.widget.TextView>(R.id.titulo_toolbar)
            ?.setOnClickListener {
                mostrarDialogoIp()
            }
    }

    // Som distinto para cada gesto — audível mesmo com o telemóvel longe, ao contrário da vibração
    private var toneGenerator: android.media.ToneGenerator? = null
    private var toneGeneratorPausar: android.media.ToneGenerator? = null

    private fun tocarSom(comando: String) {
        val prefs = requireContext().getSharedPreferences("config_app", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("som_ativo", true)) return

        // O som do "Pausar" (sinal de OK) usa um gerador à parte, com volume máximo
        if (comando == "PAUSAR") {
            if (toneGeneratorPausar == null) {
                toneGeneratorPausar = android.media.ToneGenerator(
                    android.media.AudioManager.STREAM_MUSIC,
                    100 // volume máximo
                )
            }
            toneGeneratorPausar?.startTone(android.media.ToneGenerator.TONE_PROP_NACK, 150)
            return
        }

        if (toneGenerator == null) {
            toneGenerator = android.media.ToneGenerator(
                android.media.AudioManager.STREAM_MUSIC,
                80 // volume (0-100), moderado para não ser demasiado alto
            )
        }

        val tom = when (comando) {
            "AVANCAR" -> android.media.ToneGenerator.TONE_PROP_BEEP2   // som agudo
            "RECUAR" -> android.media.ToneGenerator.TONE_PROP_BEEP    // som grave
            "ESTORE_SOBE", "LUZ_ON" -> android.media.ToneGenerator.TONE_PROP_BEEP2
            "PORTAO_TOGGLE" -> android.media.ToneGenerator.TONE_PROP_ACK
            else -> android.media.ToneGenerator.TONE_PROP_BEEP
        }

        toneGenerator?.startTone(tom, 150) // duração em milissegundos
    }

    private fun vibrarConfirmacao() {
        val prefs = requireContext().getSharedPreferences("config_app", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("vibracao_ativa", true)) return

        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val manager = requireContext().getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            requireContext().getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(60, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(60)
        }
    }

    // Menu de definições (painel que sobe do fundo)
    private fun mostrarDialogoIp() {
        val ctx = requireContext()
        val prefs = ctx.getSharedPreferences("config_app", android.content.Context.MODE_PRIVATE)
        val casa = prefs.getString("modo_alvo", "APRESENTACAO") == "ESP32"

        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
        val vista = layoutInflater.inflate(R.layout.dialog_definicoes, null)
        dialog.setContentView(vista)
        dialog.setCancelable(false)
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val subtitulo = vista.findViewById<android.widget.TextView>(R.id.ds_subtitulo_modo)
        val tituloIp = vista.findViewById<android.widget.TextView>(R.id.ds_titulo_ip)
        val ajudaIp = vista.findViewById<android.widget.TextView>(R.id.ds_ajuda_ip)
        val campoPc = vista.findViewById<android.widget.EditText>(R.id.ds_campo_ip_pc)
        val campoCasa = vista.findViewById<android.widget.EditText>(R.id.ds_campo_ip_esp)
        val switchVibracao = vista.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.ds_switch_vibracao)
        val switchSom = vista.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.ds_switch_som)
        val botaoGuardar = vista.findViewById<android.widget.Button>(R.id.ds_botao_guardar)
        val botaoMudarModo = vista.findViewById<android.view.View>(R.id.ds_botao_mudar_modo)

        subtitulo.text = if (casa) "Casa Inteligente" else "Apresentações"
        tituloIp.text = if (casa) "IP da casa" else "IP do PC"
        ajudaIp.text = if (casa) "Aparece no Serial Monitor, depois de «Ligado! IP»"
        else "Aparece na janela do servidor no PC"
        campoPc.visibility = if (casa) android.view.View.GONE else android.view.View.VISIBLE
        campoCasa.visibility = if (casa) android.view.View.VISIBLE else android.view.View.GONE

        campoPc.setText(prefs.getString("pc_ip", ""))
        campoCasa.setText(prefs.getString("esp32_ip", ""))
        switchVibracao.isChecked = prefs.getBoolean("vibracao_ativa", true)
        switchSom.isChecked = prefs.getBoolean("som_ativo", true)

        val grupoConf = vista.findViewById<android.widget.RadioGroup>(R.id.ds_grupo_confirmacao)
        if (prefs.getString("confirmacao", "OFF") == "TODOS") {
            grupoConf.check(R.id.ds_confirm_todos)
        } else {
            grupoConf.check(R.id.ds_confirm_off)
        }

        val formatoIp = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")

        botaoGuardar.setOnClickListener {
            val ipPc = campoPc.text.toString().trim()
            val ipCasa = campoCasa.text.toString().trim()
            val ipAtivo = if (casa) ipCasa else ipPc
            val campoAtivo = if (casa) campoCasa else campoPc

            if (!formatoIp.matches(ipAtivo)) {
                campoAtivo.error = "Escreve um IP válido, ex: 192.168.1.42"
                campoAtivo.requestFocus()
                return@setOnClickListener
            }

            val escolhaConf = if (grupoConf.checkedRadioButtonId == R.id.ds_confirm_todos) "TODOS" else "OFF"
            prefs.edit()
                .putString("pc_ip", ipPc)
                .putString("esp32_ip", ipCasa)
                .putBoolean("vibracao_ativa", switchVibracao.isChecked)
                .putBoolean("som_ativo", switchSom.isChecked)
                .putString("confirmacao", escolhaConf)
                .apply()
            dialog.dismiss()
            if (Nuvem.estaLigado()) Nuvem.guardarIps(ipPc, ipCasa)
            ligarAoServidor(ipAtivo)
        }
        // Abre a janela da conta; ao fechar volta a abrir as definições (já com os IPs da conta)
        vista.findViewById<android.view.View>(R.id.ds_botao_conta).setOnClickListener {
            dialog.dismiss()
            mostrarConta { mostrarDialogoIp() }
        }
        // Histórico dos comandos enviados (só do modo em que estás)
        vista.findViewById<android.view.View>(R.id.ds_botao_historico).setOnClickListener {
            val nomeModo = if (casa) "Casa Inteligente" else "Apresentações"
            val linhas = LogDb(ctx).ultimos(50, nomeModo)
            val construtor = androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle("Histórico – $nomeModo")
                .setNegativeButton("Fechar", null)
                .setNeutralButton("Apagar") { _, _ ->
                    LogDb(ctx).apagarTudo(nomeModo)
                    Toast.makeText(ctx, "Histórico apagado", Toast.LENGTH_SHORT).show()
                }
                .setPositiveButton("Exportar PDF") { _, _ ->
                    val ficheiro = com.google.mediapipe.examples.handlandmarker.PdfHistorico.criar(ctx, nomeModo)
                    if (ficheiro == null) {
                        Toast.makeText(ctx, "Ainda não há registos neste modo", Toast.LENGTH_SHORT).show()
                    } else {
                        com.google.mediapipe.examples.handlandmarker.PdfHistorico.partilhar(ctx, ficheiro)
                    }
                }
            if (linhas.isEmpty()) {
                construtor.setMessage("Ainda não há registos.")
            } else {
                construtor.setItems(linhas.toTypedArray(), null)
            }
            construtor.show()
        }
        // Volta ao ecrã inicial para escolher outro modo
        botaoMudarModo.setOnClickListener {
            dialog.dismiss()
            if (this::gestureSocketClient.isInitialized) {
                gestureSocketClient.close()
            }
            Navigation.findNavController(requireView()).navigate(R.id.action_camera_to_tutorial)
        }

        // Fundo transparente (para se verem os cantos redondos) e painel sempre aberto
        dialog.setOnShowListener {
            val painel = dialog.findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)
            painel?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            painel?.let {
                val comportamento = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)
                comportamento.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                comportamento.skipCollapsed = true
            }
        }
        dialog.show()
    }

    // Janela para criar conta, entrar ou sair
    private fun mostrarConta(aoFechar: () -> Unit) {
        val ctx = requireContext()
        val prefs = ctx.getSharedPreferences("config_app", android.content.Context.MODE_PRIVATE)

        if (Nuvem.estaLigado()) {
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle("Conta")
                .setMessage("Sessão iniciada como:\n${Nuvem.emailAtual()}")
                .setPositiveButton("Fechar", null)
                .setNegativeButton("Terminar sessão") { _, _ ->
                    Nuvem.sair()
                    Toast.makeText(ctx, "Sessão terminada", Toast.LENGTH_SHORT).show()
                }
                .setOnDismissListener { aoFechar() }
                .show()
            return
        }

        val caixa = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 30, 60, 0)
        }
        val campoEmail = android.widget.EditText(ctx).apply {
            hint = "E-mail"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val campoPalavra = android.widget.EditText(ctx).apply {
            hint = "Palavra-passe (mínimo 6)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        caixa.addView(campoEmail)
        caixa.addView(campoPalavra)

        val janela = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Conta GestSlide")
            .setView(caixa)
            .setPositiveButton("Entrar", null)
            .setNeutralButton("Criar conta", null)
            .setNegativeButton("Cancelar", null)
            .create()

        // Depois de entrar: se a conta já tem IPs, usa-os; senão guarda os que a app já tinha
        val aposSucesso = {
            janela.dismiss()
            Nuvem.carregarIps { ipPc, ipCasa ->
                if (!ipPc.isNullOrEmpty() || !ipCasa.isNullOrEmpty()) {
                    prefs.edit()
                        .putString("pc_ip", ipPc ?: "")
                        .putString("esp32_ip", ipCasa ?: "")
                        .apply()
                } else {
                    Nuvem.guardarIps(
                        prefs.getString("pc_ip", "") ?: "",
                        prefs.getString("esp32_ip", "") ?: ""
                    )
                }
            }
            Toast.makeText(ctx, "Sessão iniciada", Toast.LENGTH_SHORT).show()
        }

        janela.setOnShowListener {
            janela.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val email = campoEmail.text.toString().trim()
                val palavra = campoPalavra.text.toString()
                if (email.isEmpty() || palavra.isEmpty()) {
                    Toast.makeText(ctx, "Preenche o e-mail e a palavra-passe", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                Nuvem.entrar(email, palavra) { erro ->
                    if (erro == null) aposSucesso() else Toast.makeText(ctx, erro, Toast.LENGTH_LONG).show()
                }
            }
            janela.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val email = campoEmail.text.toString().trim()
                val palavra = campoPalavra.text.toString()
                if (email.isEmpty() || palavra.isEmpty()) {
                    Toast.makeText(ctx, "Preenche o e-mail e a palavra-passe", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                Nuvem.criarConta(email, palavra) { erro ->
                    if (erro == null) aposSucesso() else Toast.makeText(ctx, erro, Toast.LENGTH_LONG).show()
                }
            }
        }
        janela.setOnDismissListener { aoFechar() }
        janela.show()
    }

    private fun ligarAoServidor(ip: String) {
        if (this::gestureSocketClient.isInitialized) {
            gestureSocketClient.close()
        }
        gestureSocketClient = GestureSocketClient(URI("ws://$ip:8765"))
        gestureSocketClient.connect()
    }

    private fun initBottomSheetControls() {
        // init bottom sheet settings
        fragmentCameraBinding.bottomSheetLayout.maxHandsValue.text =
            viewModel.currentMaxHands.toString()
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinHandDetectionConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinHandTrackingConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinHandPresenceConfidence
            )

        // When clicked, lower hand detection score threshold floor
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdMinus.setOnClickListener {
            if (handLandmarkerHelper.minHandDetectionConfidence >= 0.2) {
                handLandmarkerHelper.minHandDetectionConfidence -= 0.1f
                updateControlsUi()
            }
        }

        // When clicked, raise hand detection score threshold floor
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdPlus.setOnClickListener {
            if (handLandmarkerHelper.minHandDetectionConfidence <= 0.8) {
                handLandmarkerHelper.minHandDetectionConfidence += 0.1f
                updateControlsUi()
            }
        }

        // When clicked, lower hand tracking score threshold floor
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdMinus.setOnClickListener {
            if (handLandmarkerHelper.minHandTrackingConfidence >= 0.2) {
                handLandmarkerHelper.minHandTrackingConfidence -= 0.1f
                updateControlsUi()
            }
        }

        // When clicked, raise hand tracking score threshold floor
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdPlus.setOnClickListener {
            if (handLandmarkerHelper.minHandTrackingConfidence <= 0.8) {
                handLandmarkerHelper.minHandTrackingConfidence += 0.1f
                updateControlsUi()
            }
        }

        // When clicked, lower hand presence score threshold floor
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdMinus.setOnClickListener {
            if (handLandmarkerHelper.minHandPresenceConfidence >= 0.2) {
                handLandmarkerHelper.minHandPresenceConfidence -= 0.1f
                updateControlsUi()
            }
        }

        // When clicked, raise hand presence score threshold floor
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdPlus.setOnClickListener {
            if (handLandmarkerHelper.minHandPresenceConfidence <= 0.8) {
                handLandmarkerHelper.minHandPresenceConfidence += 0.1f
                updateControlsUi()
            }
        }

        // When clicked, reduce the number of hands that can be detected at a
        // time
        fragmentCameraBinding.bottomSheetLayout.maxHandsMinus.setOnClickListener {
            if (handLandmarkerHelper.maxNumHands > 1) {
                handLandmarkerHelper.maxNumHands--
                updateControlsUi()
            }
        }

        // When clicked, increase the number of hands that can be detected
        // at a time
        fragmentCameraBinding.bottomSheetLayout.maxHandsPlus.setOnClickListener {
            if (handLandmarkerHelper.maxNumHands < 2) {
                handLandmarkerHelper.maxNumHands++
                updateControlsUi()
            }
        }

        // When clicked, change the underlying hardware used for inference.
        // Current options are CPU and GPU
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(
            viewModel.currentDelegate, false
        )
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long
                ) {
                    try {
                        handLandmarkerHelper.currentDelegate = p2
                        updateControlsUi()
                    } catch(e: UninitializedPropertyAccessException) {
                        Log.e(TAG, "HandLandmarkerHelper has not been initialized yet.")
                    }
                }

                override fun onNothingSelected(p0: AdapterView<*>?) {
                    /* no op */
                }
            }
    }

    // Update the values displayed in the bottom sheet. Reset Handlandmarker
    // helper.
    private fun updateControlsUi() {
        fragmentCameraBinding.bottomSheetLayout.maxHandsValue.text =
            handLandmarkerHelper.maxNumHands.toString()
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdValue.text =
            String.format(
                Locale.US,
                "%.2f",
                handLandmarkerHelper.minHandDetectionConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdValue.text =
            String.format(
                Locale.US,
                "%.2f",
                handLandmarkerHelper.minHandTrackingConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdValue.text =
            String.format(
                Locale.US,
                "%.2f",
                handLandmarkerHelper.minHandPresenceConfidence
            )

        // Needs to be cleared instead of reinitialized because the GPU
        // delegate needs to be initialized on the thread using it when applicable
        backgroundExecutor.execute {
            handLandmarkerHelper.clearHandLandmarker()
            handLandmarkerHelper.setupHandLandmarker()
        }
        fragmentCameraBinding.overlay.clear()
    }

    // Initialize CameraX, and prepare to bind the camera use cases
    private fun setUpCamera() {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                // CameraProvider
                cameraProvider = cameraProviderFuture.get()

                // Build and bind the camera use cases
                bindCameraUseCases()
            }, ContextCompat.getMainExecutor(requireContext())
        )
    }

    // Declare and bind preview, capture and analysis use cases
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {

        // CameraProvider
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector =
            CameraSelector.Builder().requireLensFacing(cameraFacing).build()

        // Preview. Only using the 4:3 ratio because this is the closest to our models
        preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .build()

        // ImageAnalysis. Using RGBA 8888 to match how our models work
        imageAnalyzer =
            ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                // The analyzer can then be assigned to the instance
                .also {
                    it.setAnalyzer(backgroundExecutor) { image ->
                        detectHand(image)
                    }
                }

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalyzer
            )

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun detectHand(imageProxy: ImageProxy) {
        handLandmarkerHelper.detectLiveStream(
            imageProxy = imageProxy,
            isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation =
            fragmentCameraBinding.viewFinder.display.rotation
    }

    // Faz o gesto de verdade: vibra, toca, envia ao PC/ESP32 e regista no histórico
    private fun executarComando(dir: String) {
        vibrarConfirmacao()
        tocarSom(dir)
        if (this::gestureSocketClient.isInitialized) {
            val ligado = gestureSocketClient.isOpen
            gestureSocketClient.enviarComando(dir)
            context?.let { c ->
                val prefsLog = c.getSharedPreferences("config_app", android.content.Context.MODE_PRIVATE)
                val emCasa = prefsLog.getString("modo_alvo", "APRESENTACAO") == "ESP32"
                LogDb(c).registar(
                    if (emCasa) "Casa Inteligente" else "Apresentações",
                    dir,
                    if (emCasa) "Casa" else "PC",
                    ligado
                )
            }
        }
    }

    // Update UI after hand have been detected. Extracts original
    // image height/width to scale and place the landmarks properly through
    // OverlayView
    override fun onResults(
        resultBundle: HandLandmarkerHelper.ResultBundle
    ) {
        activity?.runOnUiThread {
            if (_fragmentCameraBinding != null) {
                val dir = resultBundle.direcaoApontar

                // Mostra o estado na caixinha por cima da câmara
                val estadoAmigavel = when (dir) {
                    "PAUSAR" -> "⏸ Pausado"
                    "AVANCAR" -> "➡ Avançar"
                    "RECUAR" -> "⬅ Recuar"
                    "LUZ_ON" -> "💡 Luz ligada"
                    "LUZ_OFF" -> "🌑 Luz desligada"
                    "ESTORE_SOBE" -> "🪟 Estore a subir"
                    "ESTORE_DESCE" -> "🪟 Estore a descer"
                    "PORTAO_TOGGLE" -> "🚪 Portão"
                    else -> "🖐 Pronto"
                }
                fragmentCameraBinding.estadoGesto.text = estadoAmigavel

                // Gesto mudou (ou a mão saiu): recomeça do zero e cancela a confirmação
                if (dir != direcaoAnterior) {
                    ultimaDirecaoEnviada = "NENHUM"
                    gestoPendente = null
                    direcaoAnterior = dir
                }

                // Que modo de confirmação está escolhido nas definições?
                val prefsConf = context?.getSharedPreferences("config_app", android.content.Context.MODE_PRIVATE)
                val modoConf = prefsConf?.getString("confirmacao", "OFF") ?: "OFF"
                val precisaConfirmar = dir != "NENHUM" && modoConf == "TODOS"

                if (dir == "NENHUM" || dir == ultimaDirecaoEnviada) {
                    // nada para fazer
                    fragmentCameraBinding.confirmacaoTexto.visibility = View.GONE
                } else if (!precisaConfirmar) {
                    // comportamento normal: envia logo
                    fragmentCameraBinding.confirmacaoTexto.visibility = View.GONE
                    executarComando(dir)
                    ultimaDirecaoEnviada = dir
                } else {
                    // precisa de confirmação: tem de manter o gesto 2 segundos
                    val agora = android.os.SystemClock.elapsedRealtime()
                    if (gestoPendente == null) {
                        gestoPendente = dir
                        inicioPendente = agora
                        vibrarConfirmacao()
                    }
                    val falta = tempoConfirmarMs - (agora - inicioPendente)
                    if (falta <= 0) {
                        fragmentCameraBinding.confirmacaoTexto.visibility = View.GONE
                        executarComando(dir)
                        ultimaDirecaoEnviada = dir
                        gestoPendente = null
                    } else {
                        val segundos = (falta + 999) / 1000
                        fragmentCameraBinding.confirmacaoTexto.text =
                            "$estadoAmigavel\n\nMantém o gesto… $segundos"
                        fragmentCameraBinding.confirmacaoTexto.visibility = View.VISIBLE
                    }
                }

                // Pass necessary information to OverlayView for drawing on the canvas
                fragmentCameraBinding.overlay.setResults(
                    resultBundle.results.first(),
                    resultBundle.inputImageHeight,
                    resultBundle.inputImageWidth,
                    RunningMode.LIVE_STREAM
                )

                // Force a redraw
                fragmentCameraBinding.overlay.invalidate()
            }
        }
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            if (errorCode == HandLandmarkerHelper.GPU_ERROR) {
                fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(
                    HandLandmarkerHelper.DELEGATE_CPU, false
                )
            }
        }
    }
}