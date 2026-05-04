package com.sonar.android.config

data class AppConfig(
    val modelPath: String  = "",
    val dictOilGas: Boolean = false,
    val dictLegal: Boolean  = false,
    val dictEconomy: Boolean = false
)
