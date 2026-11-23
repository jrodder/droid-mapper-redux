package com.jrod.droidmapper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jrod.droidmapper.data.MapStore
import com.jrod.droidmapper.ui.MapScreen
import com.jrod.droidmapper.ui.MapViewModel
import com.jrod.droidmapper.ui.MapperTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()      // the screen handles the status bar, navigation bar and keyboard itself
        val store = MapStore(filesDir)
        setContent {
            MapperTheme {
                val vm: MapViewModel = viewModel(factory = viewModelFactory { initializer { MapViewModel(store) } })
                MapScreen(vm)
            }
        }
    }
}
