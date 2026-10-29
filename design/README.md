# Handoff: Nestbox — Android-on-Android VM manager (Xiaomi-optimized)

## Overview
Nestbox is a personal-use Android app that runs full virtual Android machines inside a normal app on Xiaomi phones (MIUI / HyperOS), **without rooting the phone**. It is a VirtualBox-style manager on top of a **Twoyi-based rootless container engine**, with a feature set matching VMOS Pro (root inside the VM, LSPosed, floating window, floating ball, file/app transfer, GPS/region spoofing, snapshots, export/import) plus Xiaomi-specific stability work.

Target device: Redmi Note 14 Pro+ (Snapdragon 7s Gen 3 SM7635, Adreno 810, 12 GB, Android 16, HyperOS 3). Must also work on MediaTek Xiaomi devices.

## About the Design Files
`Nestbox VM Manager v2.dc.html` is a **design reference built in HTML** — a clickable prototype showing intended look, flows and behavior. It is **not production code**. Recreate it natively in **Kotlin + Jetpack Compose (Material 3)**. Open the HTML in a browser to click through every screen (the `startScreen` prop switches between first-run setup and home).

## Fidelity
**High-fidelity.** Colors, type, spacing, radii and copy are final. Recreate the UI closely with Compose; use Material 3 components restyled to the tokens below.

---

## 1. Architecture (build this)

```
app/                      Kotlin + Compose UI (this design)
 ├─ ui/                   screens (setup, home, wizard, detail, spoof, transfer, run)
 ├─ vm/                   VmRepository, VmConfig (Room or JSON in filesDir)
 ├─ engine/               VmEngine interface + implementations
 │   ├─ TwoyiEngine       real engine (JNI → Rust libtwoyi.so)
 │   └─ FakeEngine        simulated boot/health for UI dev on any phone/emulator
 ├─ service/VmService     foreground service, one per running VM (android:process=":vmN")
 ├─ bridge/               adb-over-socket: files, clipboard, APK install, notifications
 ├─ spoof/                SpoofProfile → written into guest system props/services
 ├─ xiaomi/               HyperOS detection + deep links to permission screens
 └─ health/               thermal, fps, memory sampling + throttle policy
engine-native/            Rust (from Twoyi fork), built with cargo-ndk
rootfs/                   guest Android images (prebuilt Twoyi 8.1 first)
```

### Engine interface (keep UI engine-agnostic)
```kotlin
interface VmEngine {
    suspend fun prepare(vm: VmConfig, onProgress: (Float, String) -> Unit) // extract rootfs
    suspend fun start(vm: VmConfig, surface: Surface)                       // boot, attach display
    fun attachSurface(vmId: String, surface: Surface?)                      // full ↔ floating window
    fun sendInput(vmId: String, ev: MotionEvent)
    suspend fun stop(vmId: String)
    fun state(vmId: String): StateFlow<VmState>   // Stopped, Booting(pct, logLine), Running, Killed
    fun health(vmId: String): Flow<Health>        // fps, tempC, ramBytes
}
```
Build `FakeEngine` FIRST so the whole UI works before native code compiles.

### Engine base
- Fork **Disable-OP/twoyi** (active, MPL-2.0) — or cyanmint/Threetwi for its multi-profile rootfs support. Keep its Rust core and renderer.
- Each VM = its own rootfs folder: `filesDir/vms/<id>/rootfs`. Multi-VM = multiple folders.
- Start with the prebuilt Android 8.1 rootfs (extract `rootfs.7z` from the Twoyi release APK into assets/ or download on first run).
- Android 10/12 guest = later phase (custom AOSP build). Show it as "BETA" in the ROM picker.

### Xiaomi stability (non-negotiable)
1. Each running VM in a **separate process** (`:vm1`, `:vm2`…) with a **foreground service** (`foregroundServiceType="specialUse"`) and an ongoing notification ("Main · Running · Stop").
2. First-run setup deep links (wrap each in try/catch, fall back to app details settings):
   - Autostart: `Intent().setComponent(ComponentName("com.miui.securitycenter","com.miui.permcenter.autostart.AutoStartManagementActivity"))`
   - Battery: `com.miui.powerkeeper/.ui.HiddenAppsConfigActivity` with extras `package_name`, `package_label`
   - Other permissions / pop-up: `Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", packageName)`
   - Overlay: `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`
   - "Lock in Recents": cannot be automated — show instruction only.
3. Detect kills: service writes heartbeat to a file every 5 s; on app open, if VM was "running" and heartbeat is stale → status `Killed` → home banner "X was stopped by HyperOS · Resume".
4. Thermal guard: `PowerManager.getThermalHeadroom()` + `ThermalStatusListener`; at ≥ MODERATE (or 42 °C) drop guest fps to 30 and lower CPU priority; show amber temp chip.
5. Presets instead of sliders: Battery (2 cores, 2 GB cap, 30 fps, pause when hidden), Balanced (4, 3 GB, 60), Performance (6, 4 GB, 90). Caps are cgroup-style limits, not reservations.
6. 64-bit only (`abiFilters "arm64-v8a"`). Detect Adreno vs Mali via `GLES20.glGetString(GL_RENDERER)`; Mali → compatibility renderer flag.
7. Auto-snapshot guest `/data` state marker every few minutes so "Resume" is fast.

### Guest features (inside rootfs)
- Root: Magisk su in Geek ROM; toggle = enable/disable su daemon on next boot.
- LSPosed: preinstalled in Geek ROM; toggle.
- Spoof: write `persist.sys.timezone`, locale, `gsm.sim.operator.numeric`, `gsm.sim.operator.iso-country`, `ro.product.model/brand/manufacturer` into guest props before boot; location via a guest-side location provider reading coordinates the host app pushes over the bridge socket (joystick updates ~1 Hz).
- Bridge: guest adbd on `127.0.0.1:5556`. Use it for APK install (`pm install`), clipboard (`cmd clipboard`/service), file push/pull. Shared folder: bind-mount `/sdcard/Nestbox` → guest `/sdcard/Host`.

### Build phases (do in order)
1. Scaffold Compose app + FakeEngine + all screens from this design.
2. VmService + Xiaomi setup + kill detection + notification.
3. Integrate Twoyi native lib; boot 8.1 rootfs into a SurfaceView; touch input.
4. Floating window (overlay `WindowManager` + TYPE_APPLICATION_OVERLAY) + floating ball.
5. Bridge: APK copy, files, clipboard.
6. Spoof profiles + joystick.
7. Snapshots / export (.nbx = tar.zst of VM folder) / repair / trim / reset.
8. Health monitor + thermal throttle.
9. (Later) custom Android 10/12 rootfs.

---

## 2. Screens

All screens: dark background `#111318`, horizontal padding 16 px (setup 20 px), vertical gap between sections 16–20 px. Cards `#1b1f27`, radius 20–24 px. List rows inside a card separated by 1 px `#252a33` top border, padding 13–14 px.

### 2.1 Xiaomi setup (first run, also from home "tune" icon)
- Eyebrow `NESTBOX · XIAOMI SETUP` — JetBrains Mono 12 px, `#4C8DFF`, letter-spacing .08em.
- Title "Prepare this phone" 28/600. Body "HyperOS closes background apps aggressively. These settings keep virtual machines running." 14 px `#9a9da3`, lh 1.5.
- 5 rows (icon 22 px `#9a9da3`, title 15/500, sub mono 11 px):
  1. memory · Container engine · "Supported · arm64 · HyperOS 3.0" (auto check)
  2. restart_alt · Autostart · "Security › Permissions › Autostart"
  3. battery_saver · Battery: No restrictions · "App info › Battery saver"
  4. lock · Lock in Recents · "Long-press Nestbox card › Lock"
  5. picture_in_picture · Floating windows · "Other permissions › Display pop-up"
- Done = filled check_circle `#4C8DFF`, sub becomes "Allowed". Not done = pill button "Fix" (`#2b303a`, h36, 13/500).
- Bottom button full width h52 pill `#4C8DFF` text `#00204d` 16/600: "Fix all" → "Continue" when all done.

### 2.2 Home — "Machines"
- Header: "Machines" 24/600 + tune icon button 44×44 (opens setup).
- Host card (radius 24, pad 16): "Redmi Note 14 Pro+ · HyperOS 3" 15/500, sub "Snapdragon 7s Gen 3 · 12 GB" mono 11.5. Right: temp chip h28 pill `#232833` with thermostat icon + "37°C". Bar "Memory used by machines  X / 12 GB" 6 px tall, track `#2b303a`, fill `#4C8DFF`.
- Killed banner (if any): bg `oklch(0.3 0.05 75)`, warning icon `oklch(0.88 0.12 75)`, "Game AFK was stopped by HyperOS" 14/500, "Last state saved 2 min ago" 12 px; button "Resume" amber fill, text `#2a1d00`.
- VM card (radius 24, pad 14, gap 14, hover `#222733`): thumbnail 44×60 radius 8 striped; name 16/500; status dot 7 px + label + "· 8.1 Geek" mono; chips (mono 10.5, pad 2/7, radius 6, `#262b36`, text `#b9c3d6`): ROOT, LSPOSED, GMS, region code, preset. Right: 44 px round button `#2b303a` play_arrow / open_in_full.
- Status colors: Running `#4C8DFF`, Powered off `#5b5e63`, Stopped by system amber `oklch(0.82 0.13 75)`.
- FAB bottom-right: h56, radius 24, `#4C8DFF`, "+ New machine" 15/600, shadow `0 8px 24px rgba(0,0,0,.4)`.

### 2.3 New machine wizard (4 steps)
Header: close/back, "New machine", "n / 4" mono. Progress: 4 bars h4 radius 2, active `#4C8DFF`, inactive `#2b303a`. Step title 24/600.
1. **Choose a system** — selectable cards (border 1.5 px, selected `#4C8DFF`):
   - Android 8.1 Geek [STABLE] "Magisk + LSPosed preinstalled · 1.2 GB"
   - Android 8.1 + Google [STABLE] "Play Store · Play Services · 1.6 GB"
   - Android 8.1 Lite [LIGHT] "Minimal system for games · 0.8 GB"
   - Android 10 [BETA] "Newer apps · less stable · 1.9 GB"
   - Import rootfs [FILE] "rootfs.7z or .nbx backup"
   Tag colors: STABLE `#9fd6a8`, BETA amber, others `#b9c3d6`.
2. **Performance** — Name input (h48, radius 12, `#1b1f27`), 3 preset cards (Battery / Balanced / Performance, subs as in §1), resolution chips 720×1600 / 1080×2400 / 900×2000, note "Limits are caps, not reservations. The machine shares the phone's kernel and only uses what it needs."
3. **Features** — toggles: Root (Magisk), LSPosed, Google services ("Adds about 400 MB"), Shared folder, Run with screen off.
4. **Review** — key/value rows; note "The system extracts once (1.2 GB). First boot takes about a minute, later boots a few seconds." Button "Create and start".

### 2.4 Machine detail
- Back; name 26/600; status line.
- Buttons: "Start / Resume / Show display" (flex, primary) + "Float" (secondary pill `#2b303a`, picture_in_picture).
- 3 shortcut tiles grid: Spoof (sub "Tokyo · JP" or "Off"), Transfer ("Apps · files"), ADB ("port 5556", copies `adb connect 127.0.0.1:5556`).
- Section "System" toggles: Root (Magisk), LSPosed, Google services, Run with screen off. Changing while running → toast "Applies on next restart".
- Section "Performance" rows: Preset, CPU · memory cap, Display "1080×2400 · 60 fps", Thermal guard "Throttle at 42°C".
- Section "Snapshots" + "Take" link; rows with history icon, name, "when · size", "Restore".
- Section "Maintenance": Export machine (.nbx), Repair boot, Trim cache, Factory reset (red `oklch(0.72 0.14 25)`).

### 2.5 Spoof profile
- Map area h190 radius 24 (use osmdroid / MapLibre in production) with pin and coords chip.
- Location chips: Real location, Tokyo, London, New York, Dhaka (+ long-press map).
- Region chips (mono): Off, JP, US, GB, BD → rows Carrier, MCC/MNC, Timezone, Locale.
  - JP: NTT docomo · 440/10 · Asia/Tokyo · ja-JP; US: T-Mobile · 310/260 · America/New_York · en-US; GB: EE · 234/30 · Europe/London · en-GB; BD: Grameenphone · 470/01 · Asia/Dhaka · bn-BD.
- Device identity chips: Real device, Xiaomi 14, Pixel 8, Galaxy S24.
- Toggle "Joystick in floating ball".
- "Apply to machine" → toast "Profile applied · reopen apps to refresh".

### 2.6 Transfer
- Segmented Apps / Files (container `#1b1f27` pill, active `#2f3a52`).
- Apps: list of installed phone apps (icon 36 px radius 10, name, size, "Copy" → "Copied" in accent). Dashed button "Install APK from storage".
- Files: shared folder card (Phone `/sdcard/Nestbox` ↔ Guest `/sdcard/Host`), recent files list, "Clipboard sync" toggle.

### 2.7 Running machine
- Top bar: back, name, LIVE/BOOT pill (accent / amber).
- Health row (3 chips, `#161a21`, radius 12): fps, temp (green `#9fd6a8` < 40 °C, amber ≥ 40), RAM.
- Guest display: SurfaceView, radius 16, margin 14. Booting state = black log console (mono 11, `#9fbcf0`) + progress bar "Starting container".
- Guest nav bar h44 (back / home / recents).
- **Floating ball**: 48 px circle `rgba(76,141,255,.92)` icon widgets/close; menu card `rgba(27,31,39,.97)` radius 20, rows h42: Guest home, Screenshot, Rotate, Location joystick, Floating window / Full screen, Power off (red).
- **Joystick**: 110 px circle bottom-left, knob 44 px accent; label with current place.
- **Floating window mode**: 190×360 overlay window over the phone home, title bar h30 with expand button. In production: system overlay, draggable, resizable, pinch to scale.

### Toasts
White `#ecedee` card, text `#111318` 14 px, radius 12, bottom 24 px, auto-hide 2.2 s.

## 3. State
```kotlin
data class VmConfig(id, name, rom: Rom, preset: Preset, res: Size, root, xposed, gms, keepAlive, shared, spoof: SpoofProfile, snapshots: List<Snapshot>)
enum class VmStatus { Stopped, Booting, Running, Killed }
data class SpoofProfile(place: String, lat: Double?, lng: Double?, region: String /*Off|JP|US|GB|BD*/, device: String, joystick: Boolean)
data class Health(fps: Int, tempC: Int, ramBytes: Long, throttled: Boolean)
```
Screen state: setup checks map, current screen, selected VM, wizard step + draft, run mode (full | floating), ball open, transfer tab, clipboard sync.

## 4. Design tokens
Colors
- bg `#111318`, deep bg `#0c0e12`, console `#08090a`
- surface `#1b1f27`, surface-2 `#232833`, surface-3 `#2b303a`, hover `#222733`, divider `#252a33`, outline `#3a4050`
- text `#ecedee`, text-2 `#c4c6ca`, text-3 `#9a9da3`, text-4 `#6b6e73`
- accent `#4C8DFF`, on-accent `#00204d`, accent-container `#1f2f4d`
- amber `oklch(0.82 0.13 75)` (≈ `#E8B04B`), green `#9fd6a8`, red `oklch(0.72 0.14 25)` (≈ `#E5776A`)

Type
- UI: **Roboto Flex** 400/500/600 (Compose: bundle as font resource or use system Roboto)
- Mono: **JetBrains Mono** 400/500 — specs, paths, chips
- Scale: 28 / 26 / 24 / 16 / 15 / 14 / 13 / 12 / 11.5 / 11 / 10.5

Radius: 999 (pills), 24 (big cards, FAB), 20 (list cards), 16 (display), 12 (inputs, toasts, chips-square), 8 (thumb), 6 (tags)
Spacing: 4, 6, 8, 10, 12, 14, 16, 18, 20, 24
Touch targets ≥ 44 px. Icons: **Material Symbols Rounded** (use `androidx.compose.material.icons.extended` Rounded set).

## 5. Assets
No images. Icons = Material Symbols Rounded. Guest screen, map and thumbnails are placeholders in the prototype.

## 6. Files
- `Nestbox VM Manager v2.dc.html` — current design (open in browser; needs `support.js` and `android-frame.jsx` alongside)
- `Nestbox VM Manager.dc.html` — earlier v1 (VM sliders concept, superseded)

## 7. Risks
- Twoyi ROM can't be built from source yet → 8.1 guest first; custom 10/12 is a separate big project.
- Banking / Play Integrity apps will detect the container.
- HyperOS updates can change permission screens → keep deep links in one file with fallbacks.
- Spoofing in games/services can break their terms.
