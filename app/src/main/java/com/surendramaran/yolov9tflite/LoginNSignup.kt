package com.surendramaran.yolov9tflite

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class LoginNSignup : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        // Decide whether to show login or signup layout
        val showSignup = intent.getBooleanExtra("showSignup", false)

        if (showSignup) {
            setContentView(R.layout.signup)
            setupSignup()
        } else {
            setContentView(R.layout.login)
            setupLogin()
        }
    }

    private fun setupLogin() {
        val emailField = findViewById<EditText>(R.id.emailField)
        val passwordField = findViewById<EditText>(R.id.passwordField)
        val loginButton = findViewById<Button>(R.id.loginButton)

        loginButton.setOnClickListener {
            val email = emailField.text.toString().trim()
            val password = passwordField.text.toString().trim()

            if (email.isBlank() || password.isBlank()) {
                Toast.makeText(this, "Please enter email and password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Toast.makeText(this, "Login successful!", Toast.LENGTH_SHORT).show()

                        // ✅ Navigate to MainActivity here
                        val intent = Intent(this, MainActivity::class.java)
                        startActivity(intent)
                        finish() // Optional: prevent returning to login on back press

                    } else {
                        Toast.makeText(this, "Login failed: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                    }
                }
        }

        val signUpNowText = findViewById<TextView>(R.id.signUpText)
        signUpNowText.setOnClickListener {
            val intent = Intent(this, LoginNSignup::class.java)
            intent.putExtra("showSignup", true)
            startActivity(intent)
            finish()
        }
    }

    private fun setupSignup() {
        val fullNameField = findViewById<EditText>(R.id.fullNameField)
        val emailField = findViewById<EditText>(R.id.emailField)
        val passwordField = findViewById<EditText>(R.id.passwordField)
        val confirmPasswordField = findViewById<EditText>(R.id.confirmPasswordField)
        val signupButton = findViewById<Button>(R.id.signupButton)

        signupButton.setOnClickListener {
            val fullName = fullNameField.text.toString().trim()
            val email = emailField.text.toString().trim()
            val password = passwordField.text.toString().trim()
            val confirmPassword = confirmPasswordField.text.toString().trim()

            if (fullName.isBlank() || email.isBlank() || password.isBlank() || confirmPassword.isBlank()) {
                Toast.makeText(this, "All fields are required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (password != confirmPassword) {
                Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val uid = auth.currentUser?.uid ?: return@addOnCompleteListener
                        val userData = hashMapOf(
                            "fullName" to fullName,
                            "email" to email
                        )
                        db.collection("users").document(uid).set(userData)
                            .addOnSuccessListener {
                                Toast.makeText(this, "Signup successful!", Toast.LENGTH_SHORT).show()
                                // Go to login or main activity
                            }
                            .addOnFailureListener {
                                Toast.makeText(this, "Failed to save user info", Toast.LENGTH_SHORT).show()
                            }
                    } else {
                        Toast.makeText(this, "Signup failed: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                    }
                }
        }
        val loginInsteadText = findViewById<TextView>(R.id.loginText)
        loginInsteadText.setOnClickListener {
            val intent = Intent(this, LoginNSignup::class.java)
            intent.putExtra("showSignup", false)
            startActivity(intent)
            finish()
        }
    }
}

