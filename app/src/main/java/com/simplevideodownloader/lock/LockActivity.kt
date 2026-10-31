package com.simplevideodownloader.lock

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.simplevideodownloader.R
import com.simplevideodownloader.databinding.ActivityLockBinding

/** Pantalla de bloqueo: pide la huella al abrirse. Atrás cierra la app en lugar de saltarse el bloqueo. */
class LockActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLockBinding
    private lateinit var prompt: BiometricPrompt

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                AppLock.unlocked = true
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                binding.message.text = errString
            }
        })
        binding.unlockButton.setOnClickListener { authenticate() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishAffinity()
        })
        // Si ya no hay huella ni bloqueo de pantalla en el teléfono, el bloqueo no se puede usar: se abre la app.
        if (!AppLock.isAvailable(this)) {
            AppLock.unlocked = true
            finish()
            return
        }
        if (savedInstanceState == null) authenticate()
    }

    private fun authenticate() {
        binding.message.setText(R.string.lock_message)
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.lock_title))
                .setSubtitle(getString(R.string.app_name))
                .setAllowedAuthenticators(AppLock.AUTHENTICATORS)
                .build()
        )
    }
}
