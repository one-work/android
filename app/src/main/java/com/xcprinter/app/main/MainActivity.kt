package com.xcprinter.app.main

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.xcprinter.app.R
import com.xcprinter.sdk.bluetooth.BluetoothPermissions
import dev.hotwire.navigation.activities.HotwireActivity
import dev.hotwire.navigation.tabs.HotwireBottomNavigationController
import dev.hotwire.navigation.tabs.navigatorConfigurations
import dev.hotwire.navigation.util.applyDefaultImeWindowInsets

class MainActivity : HotwireActivity() {
  private lateinit var bottomNavigationController: HotwireBottomNavigationController

  private val permissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)

    setContentView(R.layout.activity_main)
    findViewById<android.view.View>(R.id.root).applyDefaultImeWindowInsets()

    initializeBottomTabs()
    requestBluetoothPermissions()
  }

  private fun initializeBottomTabs() {
    val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_nav)

    bottomNavigationController = HotwireBottomNavigationController(
      activity = this,
      view = bottomNavigationView,
      lazyLoadTabs = true
    )
    bottomNavigationController.load(mainTabs)
  }

  override fun navigatorConfigurations() = mainTabs.navigatorConfigurations

  /** 权限声明和所需权限列表由 SDK 提供，运行时授权仍由宿主 Activity 发起。 */
  private fun requestBluetoothPermissions() {
    if (!BluetoothPermissions.hasPermissions(this)) {
      permissionLauncher.launch(BluetoothPermissions.required())
    }
  }
}
