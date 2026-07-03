# JiggleMouse

A minimal Android app that turns your phone into a Bluetooth HID mouse and
nudges the cursor by a couple of pixels on a timer, to keep a paired
computer's screen awake.

Requires **Android 9 (API 28) or newer** — that's an OS requirement for the
Bluetooth HID Device role, not something this app can work around. Some
phone manufacturers also disable this profile; if you never see your phone
listed as connectable from your PC's Bluetooth settings, that's usually why.

## How it works

1. Open JiggleMouse and wait for the status to say "Ready."
2. **Don't manually pair through Android's Bluetooth settings.** Instead, tap
   **Scan for nearby devices** in the app and let it find your computer over
   a live scan (make sure your computer's Bluetooth is on and discoverable —
   e.g. its own Bluetooth settings panel open).
3. Pick your computer from the dropdown (new/unpaired devices are labeled
   "(new)", already-paired ones "(paired)") and tap **Connect**.
4. Accept the pairing prompt that appears on your computer. If your phone
   also shows a confirmation prompt, accept that too — with numeric
   comparison pairing both sides usually show the same code.
5. Once it says "Connected... as a mouse," pick a **jiggle style**:
   - **Silent** — a tiny 2px twitch and back. Minimal, invisible, robotic.
   - **Human-like** — a short curved drift in a random direction over
     several small steps with eased timing, a brief pause, then a drift
     back — closer to how a hand actually nudges a mouse than a single
     robotic twitch.
   - **Active work** — 2–4 separate small moves in varying directions and
     distances, one after another, without returning to the start —
     resembles a brief moment of someone actually using the mouse (scanning
     a page, nudging toward something) rather than a jiggle-and-return.
6. Set the **min/max seconds between jiggles**. Each time, a random value
   in that range is picked, so the timing doesn't look mechanically regular.
   Minimum allowed is 5 seconds; max must be greater than min.
7. Flip the **Jiggle** switch on.

### Language

Tap **EN** / **TR** in the top corner to switch the app's language — this is
independent of your phone's system language and is remembered between
launches.

### Reliability

- **Battery optimization prompt**: on first launch (and any time it's not
  yet exempted), a banner explains that your phone may silently kill the
  background service to save battery, with a one-tap button to exempt the
  app. Strongly recommended — without this, jiggling can just stop after a
  few hours of screen-off with no warning.
- **Auto-reconnect**: if the Bluetooth connection drops unexpectedly (PC
  sleeps, walked out of range), the app retries automatically with a
  backoff (5s, 10s, 15s... capped at 60s) until it's back or you manually
  disconnect. Toggle this off in the Connection tab if you don't want it.
- **Settings persist**: jiggle mode, interval, schedule, and the last
  device you connected to are all remembered — force-closing and reopening
  the app (or a phone reboot) restores exactly where you left off. Only
  exception: an actual Bluetooth *connection* still needs a manual Connect
  tap after a full service restart, by design.

### Scheduling

In the Jiggle tab, turn on "Only jiggle during a set time window" and pick
start/end times (with an optional "weekdays only" restriction). Outside
that window the Bluetooth connection stays alive, but jiggling
automatically pauses — no need to remember to turn it off yourself.

### Saved device profiles

In the Connection tab, connect to a device once, give it a name (e.g. "Work
PC"), and tap "Save current connection." From then on you can reconnect to
it directly from the dropdown without re-scanning or hunting through the
paired-devices list.

### Trackpad scroll

The trackpad now supports two-finger vertical drag to scroll, in addition
to one-finger drag to move and the click buttons.

### Appearance

The Advanced tab has a System/Light/Dark override, independent of your
phone's system theme setting.

### Runs in the background

Jiggling runs inside a foreground Service with a persistent notification
(Android requires the notification — that's the trade-off for being allowed
to keep running with the screen off). You can lock your phone or switch to
another app and it keeps going. To stop it, either turn the Jiggle switch
off in the app, or swipe away / tap the persistent "JiggleMouse" notification
area (force-stopping the app from Android's app-info screen also stops it).

### Interval control

Set your own **min/max seconds** between jiggles — a random value in that
range is picked each time. There's no forced minimum beyond 1 second; if you
want it moving constantly you can set both min and max to the same low
value. Only rule: min can't exceed max (they're auto-swapped if you enter
them backwards).

### Stealth: name spoofing

Under "Bluetooth device name," pick a common real-mouse name from the list
(or enter a custom one) and tap **Apply name**. This changes your phone's
actual Bluetooth adapter name — what your computer (and any other nearby
Bluetooth device) sees during pairing/discovery — to match. Tap **Restore
original name** when you're done; the app remembers your phone's original
name automatically the first time you change it.

Note this changes your phone's Bluetooth name system-wide, not just for
this connection, until you restore it.

### Trackpad mode

Tap **Open trackpad** (after connecting) to use your phone as a real mouse:
drag on the surface to move the cursor, tap **Left click** / **Right
click** to click. Uses the same connection the jiggle feature uses.

### Keyboard mode

Tap **Open keyboard mode** to use your phone as a Bluetooth keyboard —
type in the box and keystrokes are sent live. This is a **completely
separate HID identity** from the mouse: only one can be registered with
Bluetooth at a time, so opening keyboard mode fully releases the mouse
connection first, and closing it hands the connection back to the mouse
(you'll need to tap Connect again on the main screen). This means keyboard
mode can never leak into or affect the mouse's stealth identity or jiggle
state — they're mutually exclusive by design, not just by convention.

### Language

Tap **EN** / **TR** in the top corner to switch the app's language — this is
independent of your phone's system language and is remembered between
launches. Every piece of UI text, status message, toast, and notification
is covered by both languages (device brand names in the name-spoofing list
are intentionally left as-is in both, since they're real product names).

### If you already manually paired through Settings and it's stuck

If you paired via Android's Bluetooth settings *before* ever opening this
app, your computer cached that pairing without knowing a mouse service
exists on the phone — later connect attempts will hang or bounce. Fix:
forget/unpair the device on **both** your phone's and your computer's
Bluetooth settings (not just one side — a mismatch there causes the
handshake to hang, showing a pairing prompt on only one device). Then use
the in-app **Scan** button to find it fresh and connect from there, rather
than pairing manually again.

## Building the APK (no Android Studio needed)

This repo includes a GitHub Actions workflow that builds a debug APK in the
cloud. To use it:

1. Push this project to a new GitHub repository (or fork it if you put it
   up as one).
2. Go to the **Actions** tab on GitHub — a workflow run will start
   automatically (or trigger it manually via "Run workflow").
3. When it finishes, download the `app-debug` artifact from the run — that
   zip contains your installable `app-debug.apk`.
4. Copy it to your phone and install it (you'll need to allow "install from
   unknown sources" for whichever app you use to open it, one time).

No local Android SDK, Gradle, or Studio install required — GitHub's
runners have everything and build it for you.

## Notes / limitations

- This uses the standard Android `BluetoothHidDevice` API (classic
  Bluetooth, not BLE), the same one legitimate remote-control/keyboard apps
  use — no special/hidden APIs, no root required.
- The jiggle only runs while the app is in the foreground and the screen is
  on `unless you additionally wire it into a foreground Service — see
  `MainActivity.kt` for where the timer logic lives if you want to move it
  into a Service for background operation.
- Only one report descriptor (a plain relative-movement mouse: buttons + X
  + Y) is registered — no scroll wheel, since it's not needed for jiggling.
