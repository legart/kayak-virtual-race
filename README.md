# Kayak Virtual Race (for Dansprint ergometers) — Arcade Kayak Sprint Edition

A modern, cross-platform desktop application (Kotlin + Compose Multiplatform Desktop) that communicates directly with **Dansprint** kayak/canoe ergometers over **USB HID**, re-implementing the **Dansprint Virtual Race** race protocol with an arcade-style sprint regatta display, built-in 8-ergometer wire-level emulator, and mobile Wi-Fi remote control.

> **Disclaimer**: This is an independent, unofficial open-source project developed for interoperability with Dansprint ergometer hardware. It is **not affiliated with, endorsed by, or supported by Dansprint ApS**. All product names, trademarks, and registered trademarks (`Dansprint`, `Moebius`) are the property of their respective owners. Use at your own risk.

---

## Key Features

- **Full Dansprint USB HID Compatibility**: Supports up to **8 physical ergometers simultaneously** (`VID=0x16C0`, `PID=0x1503`) with automatic USB-to-lane mapping, 500 kHz hardware timebase interpolation, leader progress broadcasts, official finish times, and 2-strike false-start disqualification.
- **Built-in Wire-Level Emulator**: Test and race without physical hardware using up to 8 virtual ergometers that parse and emit the exact framed binary packets (`FF FF FF FB` / `FC` / `FE` / `"READ"`), complete with AI pacing profiles, manual `SPACEBAR` paddling, boost/spin-out test triggers, and a live TX/RX hex inspector.
- **Arcade Regatta Track**: Real-time animated kayak sprint course with dynamic lane visibility (auto-hides disconnected lanes, supports USB hotplugging), live gap-to-next-boat readouts, dynamic turbo boost flames driven by real ergometer acceleration/sprint telemetry, course zoom, and race voice cues + arcade audio.
- **Mobile Web Race Remote**: Built-in zero-dependency HTTP server (default port `8080`) with an in-app **QR code** so a coach or race director can configure, start, abort, and monitor races from any phone or tablet on the same Wi-Fi network.
- **Automatic Result & Communication Logging**: Race results are automatically appended to `./results/Results_YYYY_MM_DD.txt`, full bidirectional per-race ergometer wire traffic (`TX`, `RX`, raw hex, decoded fields, and state/anomaly diagnostics) is logged to `./logs/Race_<timestamp>_No<N>_<dist>m.log` for debugging, and your configured lane count is remembered across runs.

---

## Requirements & Running from Source

### Prerequisites

- **Gradle Wrapper** (included: `./gradlew` on Linux/macOS, `gradlew.bat` on Windows)
- **JDK 25** (configured via `kotlin { jvmToolchain(25) }` in `build.gradle.kts`; Gradle's Foojay toolchain resolver can auto-download a matching JDK if not installed locally)

### Run the Application

```bash
# Linux / macOS
./gradlew run

# Windows (PowerShell / Command Prompt)
gradlew.bat run
```

> The build automatically passes `--enable-native-access=ALL-UNNAMED` to the JVM, which is required by JNA for native USB HID access on modern JDKs.

### Run Tests

```bash
./gradlew test
```

### Build Native Installers

Compose Desktop native packaging is configured for `.deb` (Linux), `.msi` (Windows), and `.dmg` (macOS):

```bash
./gradlew packageDistributionForCurrentOS
# Or target a specific format on its respective OS:
./gradlew packageDeb
./gradlew packageMsi
./gradlew packageDmg
```

---

## How to Use the Application

### 1. Choosing Transport Mode (Emulator vs. Physical USB)

At the top right of the lobby header bar, switch between:

- **`🔌 PHYSICAL USB HID`** *(default on startup)* — Scans the USB bus for real Moebius Dansprint ergometers (`16C0:1503`), opens them in non-blocking mode, and performs automatic lane discovery. Physical USB mode hides the Emulator Lab panel to maximize track space and enables 1-second USB hotplug polling.
- **`🎮 EMULATOR (VIRTUAL ERGOS)`** — Runs up to 8 simulated Dansprint ergometers over the full binary protocol. Great for demos, testing, or solo paddling with the `SPACEBAR`.

Click **`🔄 Connect`** at any time to re-scan the USB bus and re-run the ergometer discovery handshake (`0x0F` → `0x0E`).

### 2. Configuring a Race (`⚙️ SETUP`)

Click **`⚙️ SETUP`** in the control bar to configure:

- **Race Number**: Heat/race counter (auto-increments when clicking **New Race** after a completed race).
- **Race Distance**: Presets for `100m`, `200m`, `500m`, `1000m`, `2000m`, and `5000m` (immediately broadcasts Opcode `0x07` to all connected ergometers).
- **Configured Regatta Lanes (`1–8`)**: Maximum number of lanes to display/manage. This setting is persisted across restarts in `${XDG_CONFIG_HOME:-~/.config}/virtual-race/settings.properties`.
- **Display Paddler Weight (Kg)**: Toggles paddler weight display (reported by ergometers via Type 3 and Type 1 Opcodes `0x14`/`0x15`).
- **100ms USB Poll Test Mode**: Periodically polls connected ergometers with Opcode `0x0E` every 100 ms for diagnostics.
- **Emulator Hardware Lane Numbers** *(Emulator mode only)*: Lets you reassign what 1-based lane number each virtual USB unit reports in Opcode `0x0E` (useful for testing arbitrary USB port ordering or duplicate lane conflict detection).

### 3. Lane Status & Activation

Each connected ergometer automatically reports its hardware lane number (`1..8`). Only connected (or in-race / alerted) lanes are shown on the track.

- **Activate / Deactivate Lanes** *(in Pre-Race Lobby)*:
  - Click a lane's dock card on the left side of the track, **or**
  - Press `Shift + 1..8` to activate a lane (`Shift + 9` for all) and `Ctrl + 1..8` to deactivate a lane (`Ctrl + 9` for all, sending `0x1A`), **or**
  - In Physical USB mode, an ergometer entering ready state automatically sends a Type 3 (`FF FF FF FE`) packet that activates its lane.
- **Box Status Indicators**:
  - **Green**: Connected & mapped cleanly to a unique lane.
  - **Red**: Disconnected / no ergometer detected for this lane.
  - **Blue**: **Duplicate lane conflict** — two or more connected ergometers are configured with the same hardware lane number. Change one ergometer's lane number and click **Connect**.
  - **Purple**: **Power reapplied (`"READ"`)** — the ergometer was power-cycled or reset. Click **Connect** to re-initialize before starting a race.
  - **Yellow**: USB handle closed unexpectedly.

### 4. Running a Race (10-State Official Sequence)

1. Verify all racing lanes are **Connected** and **Active**.
2. Click **`🚀 START RACE`** (or press `Ctrl + Alt + Z`).
3. The application executes the exact Dansprint starting sequence with traffic lights and voice cues:
   - **Stop Paddling** *(Red light, `STOP_PAD.wav`)*: Waits until all active flywheels slow down / stop paddling for 1.5 s.
   - **Start Position** *(Red light, `START_PO.wav`)*: Prompts paddlers to take start position and waits until all paddles are still (`msSincePulse >= 500 ms`).
   - **Ready / Start Within 10s** *(Yellow light, `START_WI.wav`)*: Arms automatic **False Start Detection** (`msSincePulse < 300 ms` and `flywheelMetric > 50.0`) and waits a randomized `1.8 s – 3.8 s` gate delay.
   - **False Start** *(Flashing Red + Yellow, `FALSE_START.wav`)*: Triggered automatically if a paddler jumps the start (or manually via **`📣 FALSE START`** / `Ctrl + Alt + N`). A lane's **2nd false start** automatically **disqualifies (`DSQ`)** it, then restarts the start sequence for the remaining lanes.
   - **Race Start & Racing** *(Green light, `RACE_START.wav`)*: Sends `0x0A` to start all ergometer clocks. Boats smoothly interpolate at 40 Hz using dead-reckoning between incoming Type 2 telemetry frames, and the host broadcasts the leader's position every second.
   - **Finish & Podium**: When all active lanes cross the finish line (receiving official finish times via Opcode `0x07`), final rankings are calculated, results are saved to `./results/Results_YYYY_MM_DD.txt`, and the **Race Podium** overlay opens automatically.
4. Click **`🏁 NEXT RACE`** / **`🏁 NEW RACE`** (`Ctrl + Alt + C`) to reset ergometers (`0x12` + `0x1A`) and increment the race number for the next heat.

### 5. Emulator Lab & Wire Protocol Inspector (`🔬 Show/Hide Wire Lab`)

Open the side panel to access two tabs:

- **`🧪 EMULATOR LAB`**:
  - Toggle individual virtual ergometers plugged in (`🔌 ON` / `🔌 OFF`).
  - Switch any lane between `🤖 AI` and `🎮 SPACE` (manual player — tap **`SPACEBAR`** or click **`🚣 PADDLE!`** to paddle).
  - Trigger test events per lane: **`🔥 Boost`**, **`🌀 Spin`**, **`⚠️ JumpStart`** (injects pre-start Opcode `0x04` strokes to test stillness hold and false starts), and **`⚡ 'READ' Reset`** (injects the ASCII `"READ"` power-cycle marker).
- **`📡 WIRE HEX`**:
  - Live scrollable log of the last 120 `TX` (Host → Ergo) and `RX` (Ergo → Host) framed hex packets with decoded summaries (works in both Emulator and Physical USB HID modes).

### 6. Mobile Web Remote (`📱 :8080`)

On startup, `RaceWebServer` binds to `0.0.0.0:8080` (falling back through `8080..8090` or an ephemeral port if `8080` is busy).

- Click **`📱 :8080`** in the top bar to display a **QR code**, a **random 4-character session PIN**, and the LAN URL (e.g., `http://192.168.1.42:8080/?pin=7K4M`).
- Scanning the QR code automatically authenticates the mobile client (stripping `?pin=...` from the browser URL bar and sending it in the `X-Pin` header on every same-origin API call). Cross-origin / external API requests without the session PIN are rejected (`401`/`403`). If someone opens the bare URL manually, they are prompted for the 4-character PIN.
- Scan the QR code with any phone or tablet on the same Wi-Fi network to:
  - Start, abort, call false start, finish early, reconnect USB, or start a new race
  - Change race distance, lane count, race number, display weight, or emulator/USB mode
  - Activate/deactivate individual lanes or all lanes
  - Monitor live race timer, distances, speeds, gaps, and final podium standings
- **Custom Port**: Set either the environment variable `VIRTUALRACE_WEB_PORT` or JVM property `-Dvirtualrace.web.port=<port>`.

### 7. Keyboard Shortcuts

| Shortcut | Action |
| :--- | :--- |
| **`Ctrl + Alt + C`** | New Race |
| **`Ctrl + Alt + Z`** | Start Race |
| **`Ctrl + Alt + K`** | Abort / Restart Race |
| **`Ctrl + Alt + M`** | Finish / End Race Early |
| **`Ctrl + Alt + N`** | Trigger Manual False Start |
| **`Shift + 1..8`** | Activate Lane `1..8` (in Pre-Race Lobby) |
| **`Shift + 9`** | Activate all connected lanes |
| **`Ctrl + 1..8`** | Deactivate Lane `1..8` (sends `0x1A`) |
| **`Ctrl + 9`** | Deactivate all lanes |
| **`Up Arrow` / `Down Arrow`** | Zoom In / Zoom Out on the race track (down to 12.5 m window tracking the leader) |
| **`Spacebar`** | Manual paddle stroke (in Emulator mode) |

---

## USB Hardware & OS Setup (Technical Details)

### USB Device & Protocol Architecture

Moebius Dansprint ergometers present themselves as standard **USB HID** (Human Interface Device) class devices:

| Parameter | Value |
| :--- | :--- |
| **Vendor ID (VID)** | `0x16C0` (`5824`) |
| **Product ID (PID)** | `0x1503` (`5379`) |
| **Manufacturer String** | `"Moebius"` |
| **Product String** | `"Dansprint"` |
| **Max Simultaneous Devices** | `8` (`lanes 1..8`) |
| **HID Output Report (Host → Ergo)** | `41 bytes` — Byte `0` is Report ID `0x00`, Bytes `1..40` carry the framed packet (zero-padded) |
| **HID Input Report (Ergo → Host)** | `40 bytes` — read non-blocking into a 200-byte per-lane stream buffer (`ErgoStreamParser`) |
| **Hardware Timebase** | `500 kHz` (`1 tick = 2 µs = 0.002 ms`) |

- **Arbitrary USB Port Ordering**: You can plug ergometers into any USB port or hub in any order. During discovery, the app sends `0x0F` (init) and `0x0E` (status poll); each ergometer replies with its configured 1-based lane number (`1..8`), and the engine builds `laneToUsb` / `usbToLane` mapping tables automatically.
- **USB Hub Recommendation**: When connecting 4–8 ergometers to a laptop, use a **powered USB 2.0/3.0 hub** to avoid bus power brownouts that cause ergometers to reset and emit `"READ"` markers.
- **Full Wire Specification**: See [DANSPRINT_PROTOCOL.md](DANSPRINT_PROTOCOL.md) for the complete reverse-engineered frame layouts, opcodes, checksum algorithm, and state machine specification.

`UsbHidErgoTransport` uses two platform-specific native backends via **JNA**:

| OS | Native Backend | External Library Needed? | Kernel Driver | Extra OS Setup |
| :--- | :--- | :--- | :--- | :--- |
| **Linux** | Direct `/dev/hidraw*` via JNA `libc` | **None** (no `libhidapi` needed) | Built-in `usbhid` / `hid-generic` | **`udev` rule required** for non-root access |
| **Windows** | `hidapi` via JNA | **Yes — 64-bit `hidapi.dll`** | Built-in `hidusb.sys` / `hidclass.sys` | Put `hidapi.dll` on `PATH` / app dir; allow firewall port for web remote |
| **macOS** | `hidapi` via JNA | **Yes — `libhidapi.dylib`** (`brew install hidapi`) | Built-in IOKit HID | Pass `-Djna.library.path` if needed; Input Monitoring permission if prompted |

---

### Linux Setup (Direct `hidraw` & `udev` Rules)

#### How It Works on Linux

On Linux, `UsbHidErgoTransport` **does not require `libhidapi` or `libusb`**. Instead, it talks directly to the Linux kernel's `hidraw` subsystem via JNA bindings to `libc` (`open`, `read`, `write`, `close`):

1. **Discovery**: Scans `/sys/class/hidraw/hidraw*`, reads each device's `device/uevent` (`HID_ID=0003:000016C0:00001503`, `HID_NAME`, `HID_UNIQ`) and parent USB sysfs files (`idVendor`, `idProduct`, `manufacturer`, `product`, `serial`).
2. **Non-blocking I/O**: Opens matching `/dev/hidrawN` character devices with `O_RDWR | O_NONBLOCK`.
3. **Kernel Driver**: Because the ergometer is a standard USB HID device, the kernel's built-in `usbhid` + `hid-generic` drivers bind automatically and create `/dev/hidrawN`. No custom kernel module or driver detaching is needed.

#### Why a `udev` Rule Is Required

By default, Linux creates `/dev/hidraw*` nodes owned by `root:root` with mode `0600` (read/write only for root). Running the app as a normal user will detect the ergometers via sysfs, but `open("/dev/hidrawN", O_RDWR | O_NONBLOCK)` will fail with `EACCES` (`errno=13`) and display a permission alert in the UI.

#### Quick Install (Included Script)

Run the included helper script [install_dev_rule.sh](install_dev_rule.sh):

```bash
chmod +x install_dev_rule.sh
./install_dev_rule.sh
```

#### Manual `udev` Rule Installation

Create `/etc/udev/rules.d/99-dansprint.rules`:

```udev
SUBSYSTEM=="hidraw", ATTRS{idVendor}=="16c0", ATTRS{idProduct}=="1503", MODE="0666", TAG+="uaccess"
```

Then reload and apply the rule without rebooting:

```bash
sudo udevadm control --reload-rules
sudo udevadm trigger --subsystem-match=hidraw
```

Unplug and replug the ergometer(s) if permissions do not update immediately.

#### Technical Breakdown of the `udev` Rule

- **`SUBSYSTEM=="hidraw"`**: Targets the `/dev/hidraw*` character device nodes opened by `UsbHidErgoTransport` (not the `usb` subsystem node).
- **`ATTRS{idVendor}=="16c0", ATTRS{idProduct}=="1503"`**: Matches parent USB device attributes up the sysfs tree. **Lowercase hex** (`16c0`, `1503`) is required to match Linux sysfs values.
- **`MODE="0666"`**: Grants read/write access to all local users (convenient for shared club computers, kiosks, or non-seat sessions).
- **`TAG+="uaccess"`**: Instructs `systemd-logind` to grant dynamic ACL access to the currently active desktop session user.
  - *Stricter alternative*: If you prefer not to grant world read/write (`0666`), replace `MODE="0666"` with `MODE="0660", GROUP="plugdev", TAG+="uaccess"` (and add your user to the `plugdev` group via `sudo usermod -aG plugdev "$USER"`, then log out and back in).

#### Verifying USB & `udev` on Linux

```bash
# 1. Confirm the ergometer(s) appear on the USB bus
lsusb -d 16c0:1503

# 2. Inspect sysfs hidraw metadata (exact files read by the app)
grep -H . /sys/class/hidraw/hidraw*/device/uevent

# 3. Verify permissions on the created /dev/hidraw* node(s)
ls -l /dev/hidraw*

# 4. Inspect udev attributes if a rule does not match (replace hidraw0 as needed)
udevadm info -a -n /dev/hidraw0
```

---

### Windows Setup

#### 1. USB Driver — No Custom Driver Needed (Do NOT Use Zadig)

Windows includes built-in USB HID class drivers (`hidusb.sys` / `hidclass.sys`). When you plug in a Moebius Dansprint ergometer, Windows automatically recognizes it as a **HID-compliant vendor-defined device**.

- **No custom `.inf` driver is required.**
- **No administrator privileges** or `udev` equivalent are required to access vendor-defined USB HID devices on Windows.
- **Important**: Do **not** use Zadig to replace the driver with `WinUSB` or `libusbK`. `hidapi` on Windows communicates through the native Windows HID stack; replacing the driver with WinUSB will prevent device discovery.

#### 2. Required Native Library: `hidapi.dll`

On Windows, `UsbHidErgoTransport` uses **Backend 2 (native `hidapi` via JNA)** and loads `hidapi.dll` (verifying `hid_init()` succeeds).

To use **Physical USB HID** mode on Windows, you must provide a **64-bit `hidapi.dll`** (matching your 64-bit JVM):

1. Download the latest Windows release archive (`hidapi-win.zip`) from the official [libusb/hidapi GitHub Releases](https://github.com/libusb/hidapi/releases).
2. Extract `x64\hidapi.dll` (or `arm64\hidapi.dll` on Windows ARM64).
3. Place `hidapi.dll` in one of the following locations:
   - The application's working directory (e.g., the project root when running `gradlew.bat run`, or the installed app directory), **or**
   - Any folder on your Windows `PATH`, **or**
   - A custom directory passed to the JVM via `-Djna.library.path=C:\path\to\hidapi`.

> **Technical Note**: Windows' built-in `C:\Windows\System32\hid.dll` is Microsoft's Win32 `HidD_*` API, **not** `libhidapi` — so `hidapi.dll` is still required. If `hidapi.dll` is not found, the app will display a clear status message in Physical USB mode instead of attempting to load the wrong system DLL.

#### 3. Conflicting Software, Firewall & Power Settings

- **Close Legacy Dansprint Software**: Make sure the original `Dansprint Virtual Race` (`DansprintVirtualRaceUSB.exe`) is closed before connecting, so both applications do not compete for HID input reports.
- **Windows Defender Firewall (Mobile Remote)**: When you first launch the app, Windows Firewall may ask whether to allow Java/VirtualRace on **Private networks**. Allow it if you want phones/tablets on your local Wi-Fi to reach the Mobile Race Remote (`http://<pc-ip>:8080`).
- **USB Selective Suspend**: For long regattas with USB hubs, disabling **USB selective suspend setting** in Windows *Power Options → Advanced power settings → USB settings* helps prevent Windows from suspending idle ergometers.

---

### macOS Setup

On macOS, `UsbHidErgoTransport` uses **Backend 2 (`hidapi` via JNA)** on top of Apple's built-in IOKit HID driver (no custom kernel driver required):

1. Install `hidapi` via Homebrew:
   ```bash
   brew install hidapi
   ```
2. If JNA does not automatically locate `libhidapi.dylib`, pass Homebrew's library directory when running:
   - **Apple Silicon**: `-Djna.library.path=/opt/homebrew/lib`
   - **Intel Mac**: `-Djna.library.path=/usr/local/lib`
3. If macOS restricts HID reads, grant your terminal or packaged app permission under **System Settings → Privacy & Security → Input Monitoring**.

---

## Data Files & Persistence

| Item | Location | Description |
| :--- | :--- | :--- |
| **Race Results** | `./results/Results_YYYY_MM_DD.txt` | Plain-text race log appended automatically whenever a race finishes (or is ended early), formatted compatibly with Dansprint 2.06 (position, lane, time, weight). |
| **Per-Race Comm Logs** | `./logs/Race_YYYY_MM_DD_HH-mm-ss_No<N>_<dist>m.log` | Pipe-delimited bidirectional wire trace created automatically for every race, capturing 100% of `TX` (Host → Ergo) and `RX` (Ergo → Host) traffic with decoded fields, full raw hex bytes, state transitions, gunshot clock offsets, checksum/buffer errors, stale telemetry timeouts, and final lane summaries. |
| **App Settings** | `${XDG_CONFIG_HOME:-~/.config}/virtual-race/settings.properties` | Stores persistent user settings (currently `configuredLanesCount`). On Windows without `XDG_CONFIG_HOME`, resolves to `%USERPROFILE%\.config\virtual-race\settings.properties`. |
| **Linux udev Rule** | `/etc/udev/rules.d/99-dansprint.rules` | Grants non-root read/write access to `/dev/hidraw*` for `16c0:1503` (created by `./install_dev_rule.sh`). |

---

## Troubleshooting

| Symptom | Cause & Fix |
| :--- | :--- |
| **`Permission denied on /dev/hidrawX`** (Linux) | Missing or unapplied `udev` rule. Run `./install_dev_rule.sh`, or manually install `/etc/udev/rules.d/99-dansprint.rules`, reload `udevadm`, and replug the ergometer. |
| **`hid_enumerate error` or `Native libhidapi not found`** (Windows / macOS) | `hidapi.dll` (Windows) or `libhidapi.dylib` (macOS) is missing or wrong architecture (32-bit vs 64-bit). Install 64-bit `hidapi` and place it on `PATH` / `-Djna.library.path`. |
| **Blue lane status / "Two or more ergometers have the same lane number"** | Two connected ergometers are configured to the same lane number on their ergometer displays. Assign each ergometer a unique lane number (`1..8`) and click **Connect**. |
| **Purple lane status / "Power has been reapplied"** | An ergometer rebooted or lost USB power and sent the `"READ"` marker. Check cables / use a powered USB hub, then click **Connect**. |
| **Phone cannot open Mobile Remote URL** | Ensure phone and host PC are on the same Wi-Fi/LAN subnet (no AP/client isolation) and allow inbound TCP on port `8080` (or your `VIRTUALRACE_WEB_PORT`) in the OS firewall (`ufw`, `firewalld`, or Windows Defender Firewall). |

---

## License & Trademarks

This project is licensed under the **MIT License** — see [LICENSE.md](LICENSE.md) for details.

This software is an independent community project and is not affiliated with, sponsored by, or endorsed by **Dansprint** or **Moebius**. All trademarks and brand names mentioned are used solely for hardware identification and compatibility purposes.
