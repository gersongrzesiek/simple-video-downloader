package com.simplevideodownloader.lock

import android.os.Bundle
import android.view.View
import android.view.animation.TranslateAnimation
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.simplevideodownloader.R
import com.simplevideodownloader.databinding.ActivityLockBinding

/** Pantalla de PIN: teclado numérico y cuatro puntos. Atrás cierra la app en lugar de saltarse el bloqueo. */
class LockActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLockBinding
    private val entered = StringBuilder()
    private lateinit var dots: List<View>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)
        dots = listOf(binding.dot1, binding.dot2, binding.dot3, binding.dot4)

        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")
        for (key in keys) {
            val button = layoutInflater.inflate(R.layout.item_pin_key, binding.keypad, false) as MaterialButton
            button.text = key
            button.visibility = if (key.isEmpty()) View.INVISIBLE else View.VISIBLE
            button.setOnClickListener { if (key == "⌫") erase() else type(key) }
            binding.keypad.addView(button)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishAffinity()
        })
        renderDots()
    }

    private fun type(digit: String) {
        if (entered.length >= AppLock.PIN_LENGTH) return
        entered.append(digit)
        renderDots()
        if (entered.length < AppLock.PIN_LENGTH) return
        if (AppLock.check(this, entered.toString())) {
            AppLock.unlocked = true
            finish()
            overridePendingTransition(0, 0)
        } else {
            binding.message.setText(R.string.pin_wrong)
            binding.dots.startAnimation(TranslateAnimation(-20f, 20f, 0f, 0f).apply {
                duration = 60
                repeatCount = 4
                repeatMode = TranslateAnimation.REVERSE
            })
            entered.clear()
            binding.dots.postDelayed({ renderDots() }, 300)
        }
    }

    private fun erase() {
        if (entered.isNotEmpty()) entered.deleteCharAt(entered.length - 1)
        renderDots()
    }

    private fun renderDots() {
        dots.forEachIndexed { i, dot -> dot.isActivated = i < entered.length }
    }
}
