package app.nestbox.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Balance
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.DeviceThermostat
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PictureInPicture
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.ScreenLockPortrait
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shop
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Widgets

/** Material Symbols Rounded names from the design, mapped to the Compose Rounded set. */
object NbIcons {
    val Back = Icons.AutoMirrored.Rounded.ArrowBack
    val Close = Icons.Rounded.Close
    val Tune = Icons.Rounded.Tune
    val Settings = Icons.Rounded.Settings
    val Thermostat = Icons.Rounded.DeviceThermostat
    val Warning = Icons.Rounded.Warning
    val Play = Icons.Rounded.PlayArrow
    val OpenInFull = Icons.Rounded.OpenInFull
    val Add = Icons.Rounded.Add
    val CheckCircle = Icons.Rounded.CheckCircle
    val Pip = Icons.Rounded.PictureInPicture

    // Setup
    val Memory = Icons.Rounded.Memory
    val RestartAlt = Icons.Rounded.RestartAlt
    val BatterySaver = Icons.Rounded.BatterySaver
    val Lock = Icons.Rounded.Lock
    val DeveloperMode = Icons.Rounded.DeveloperMode

    // Detail
    val Spoof = Icons.Rounded.TravelExplore
    val Transfer = Icons.Rounded.SwapHoriz
    val Terminal = Icons.Rounded.Terminal
    val Root = Icons.Rounded.AdminPanelSettings
    val Extension = Icons.Rounded.Extension
    val Shop = Icons.Rounded.Shop
    val ScreenOff = Icons.Rounded.ScreenLockPortrait
    val AspectRatio = Icons.Rounded.AspectRatio
    val History = Icons.Rounded.History
    val Share = Icons.Rounded.IosShare
    val Build = Icons.Rounded.Build
    val Clean = Icons.Rounded.CleaningServices

    // Presets
    val Battery = Icons.Rounded.BatteryStd
    val Balance = Icons.Rounded.Balance
    val Speed = Icons.Rounded.Speed

    // ROMs
    val Android = Icons.Rounded.Android
    val Bolt = Icons.Rounded.Bolt
    val Science = Icons.Rounded.Science
    val UploadFile = Icons.Rounded.UploadFile

    // Spoof, transfer
    val Pin = Icons.Rounded.LocationOn
    val Gamepad = Icons.Rounded.Gamepad
    val Paste = Icons.Rounded.ContentPaste
    val Image = Icons.Rounded.Image
    val Zip = Icons.Rounded.FolderZip
    val Text = Icons.Rounded.Description

    // Run
    val NavBack = Icons.AutoMirrored.Rounded.ArrowBackIos
    val NavHome = Icons.Rounded.RadioButtonUnchecked
    val NavRecents = Icons.Rounded.CropSquare
    val Widgets = Icons.Rounded.Widgets
    val Home = Icons.Rounded.Home
    val Camera = Icons.Rounded.PhotoCamera
    val Rotate = Icons.Rounded.ScreenRotation
    val Fullscreen = Icons.Rounded.Fullscreen
    val Power = Icons.Rounded.PowerSettingsNew

    // Settings
    val Touch = Icons.Rounded.TouchApp
    val Phone = Icons.Rounded.PhoneAndroid
    val Internet = Icons.Rounded.Language
    val Screen = Icons.Rounded.LightMode
    val Info = Icons.Rounded.Info
    val Bug = Icons.Rounded.BugReport

    fun rom(id: String) = when (id) {
        "a13gapps" -> Shop
        "a13imp", "imp" -> UploadFile
        else -> Android
    }

    val Delete = Icons.Rounded.DeleteForever

    fun preset(id: String) = when (id) {
        "battery" -> Battery
        "perf" -> Speed
        else -> Balance
    }
}
