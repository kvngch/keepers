package fr.kvngch.keepers

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.kvngch.keepers.ui.KeepersTheme
import fr.kvngch.keepers.ui.MainScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KeepersTheme {
                val vm: MainViewModel = viewModel()
                MainScreen(vm)
            }
        }
    }
}
