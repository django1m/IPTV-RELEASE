package com.iptvplayer.tv.ui.login

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.iptvplayer.tv.R
import com.iptvplayer.tv.ui.browse.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LoginActivity : FragmentActivity() {

    private val viewModel: LoginViewModel by viewModels()

    private lateinit var nameInput: EditText
    private lateinit var serverInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var loginButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var errorText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        initViews()

        // Check if already logged in - redirect immediately
        lifecycleScope.launch {
            if (viewModel.checkHasAccountSync()) {
                navigateToMain()
            }
        }
    }

    private fun initViews() {
        nameInput = findViewById(R.id.input_name)
        serverInput = findViewById(R.id.input_server)
        usernameInput = findViewById(R.id.input_username)
        passwordInput = findViewById(R.id.input_password)
        loginButton = findViewById(R.id.btn_login)
        progressBar = findViewById(R.id.progress_bar)
        errorText = findViewById(R.id.text_error)

        // Set default values for easier testing
        nameInput.setText("Mon IPTV")

        // Focus on server input first
        serverInput.requestFocus()

        loginButton.setOnClickListener {
            attemptLogin()
        }

        // Allow Enter key to submit from password field
        passwordInput.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                attemptLogin()
                true
            } else {
                false
            }
        }

        // Observe login state
        lifecycleScope.launch {
            viewModel.loginState.collectLatest { state ->
                when (state) {
                    is LoginState.Idle -> {
                        setLoading(false)
                        errorText.visibility = View.GONE
                    }
                    is LoginState.Loading -> {
                        setLoading(true)
                        errorText.visibility = View.GONE
                    }
                    is LoginState.Success -> {
                        setLoading(false)
                        Toast.makeText(this@LoginActivity, R.string.login_success, Toast.LENGTH_SHORT).show()
                        navigateToMain()
                    }
                    is LoginState.Error -> {
                        setLoading(false)
                        errorText.text = state.message
                        errorText.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun attemptLogin() {
        val name = nameInput.text.toString().trim()
        val server = serverInput.text.toString().trim()
        val username = usernameInput.text.toString().trim()
        val password = passwordInput.text.toString()

        // Basic validation
        when {
            name.isEmpty() -> {
                nameInput.error = "Requis"
                nameInput.requestFocus()
                return
            }
            server.isEmpty() -> {
                serverInput.error = "Requis"
                serverInput.requestFocus()
                return
            }
            username.isEmpty() -> {
                usernameInput.error = "Requis"
                usernameInput.requestFocus()
                return
            }
            password.isEmpty() -> {
                passwordInput.error = "Requis"
                passwordInput.requestFocus()
                return
            }
        }

        viewModel.login(name, server, username, password)
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        loginButton.isEnabled = !loading
        loginButton.text = if (loading) getString(R.string.logging_in) else getString(R.string.login_button)
        nameInput.isEnabled = !loading
        serverInput.isEnabled = !loading
        usernameInput.isEnabled = !loading
        passwordInput.isEnabled = !loading
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
