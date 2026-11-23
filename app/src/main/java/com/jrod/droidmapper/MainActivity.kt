package com.jrod.droidmapper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jrod.droidmapper.data.MapStore
import com.jrod.droidmapper.ui.MapListScreen
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
                // The app opens on the map you were last in; otherwise on the list of maps.
                var open by rememberSaveable { mutableStateOf(store.lastOpened()) }
                val id = open
                if (id == null) {
                    MapListScreen(store, onOpen = { store.setLastOpened(it); open = it })
                } else {
                    val vm: MapViewModel = viewModel(key = id, factory = viewModelFactory { initializer { MapViewModel(store, id) } })
                    MapScreen(vm, onMaps = { store.setLastOpened(null); open = null })
                }
            }
        }
    }
}
