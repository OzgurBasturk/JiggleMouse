# JiggleMouse

An Android app that turns your phone into a wireless mouse + keyboard combo
over Bluetooth, and nudges the cursor periodically to keep a connected
computer's screen awake.

Requires **Android 9 (API 28) or newer** — a hard OS requirement for the
Bluetooth HID Device role, not something the app can work around. Some
phone manufacturers also disable this profile entirely.

## What's new in this version

This is a significant rework based on real bugs found in use:

- **One combined HID identity** (mouse + keyboard in a single Bluetooth
  pairing, like a real wireless combo receiver) instead of two separate
  ones. Switching to keyboard mode used to require a fresh HID
  registration every time, which is exactly the kind of thing that
  breaks reconnection — now there's only ever one registration for the
  life of the connection, and mouse/keyboard are just different report
  types sent over it.
- **First-time setup wizard** (permissions → device name → connect) so
  you configure everything once instead of hunting through a cluttered
  screen.
- **Simplified main screen** — just connection status, the Jiggle
  toggle + mode picker, and quick links to Trackpad/Keyboard. Everything
  else (profiles, scheduling, name spoofing, theme, language, "stop
  completely") now lives in a separate **Settings** screen (gear icon,
  top right).
- **An actual Stop button** — the persistent notification now has a real
  "Stop" action, and Settings has a "Stop JiggleMouse completely" button.
  Previously there was no way to fully shut it down short of force-
  stopping the app from Android's app-info screen.
- **Fixed the "stuck at Starting…" bug** — caused by never releasing the
  Bluetooth HID profile proxy when re-registering. There's now a
  watchdog that detects a stuck registration and retries automatically.
- **Modernized visuals** — a consistent color theme, card-grouped
  sections, and a light/dark override.

## Setup

On first launch you'll go through:
1. **Permissions** — Bluetooth access.
2. **Device identity** — pick a combo-device name (e.g. "Logitech MK235
   Wireless Combo") from the list, or enter your own, and apply it.
   **Do this before connecting** — a computer caches the name it saw when
   it first paired, so changing the name after you're already connected
   won't retroactively update what's displayed there.
3. **Connect** — scan for your computer and connect.

You can redo this wizard any time from Settings → "Redo first-time setup."

### If your computer already paired with the phone before

Forget/unpair the device on **both** your phone's and your computer's
Bluetooth settings (a mismatch on only one side causes a stuck handshake),
then use Scan in the wizard or Settings to find it fresh.

## Using it

**Main screen:**
- Status + a Connect/Reconnect button (uses the last device you connected
  to, or takes you to Settings to pick one if none is known yet).
- Jiggle toggle, with three styles: **Silent** (tiny 2px twitch), **Human-
  like** (short curved drift out and back), **Active work** (multi-move
  burst mimicking real reading/browsing, biased vertical with occasional
  bigger jumps and overshoot-correction).
- Quick links to **Trackpad** (drag to move, buttons to click, two-finger
  drag to scroll) and **Keyboard** (types live over the same connection —
  no separate pairing needed anymore).

**Settings screen** (gear icon):
- Connection: scan, connect, auto-reconnect toggle, saved device profiles.
- Jiggle behavior: mode, min/max seconds between jiggles (any values, only
  constraint is min ≤ max), and a schedule (time window + optional
  weekdays-only) that auto-pauses jiggling outside it.
- Identity: device name spoofing (see the setup caveat above).
- Appearance: light/dark/system theme, EN/TR language (independent of your
  phone's system language).
- Danger zone: redo setup, or fully stop the app.

## Runs in the background

Jiggling runs inside a foreground Service with a persistent notification
(Android requires this to keep working with the screen off). Auto-
reconnect retries with backoff (5s → 60s capped) if the connection drops
unexpectedly. To actually stop everything, use the Stop button on the
notification or in Settings — closing the app alone does not stop it, by
design, since the whole point is unattended operation.

## Keyboard layout support

The on-screen typing area uses whatever system keyboard/IME you have set
on your phone — that part was never limited. What's limited is translating
typed characters into Bluetooth HID key codes, which are physical-position
based, not character based: what a position types as depends entirely on
your **computer's** OS keyboard layout setting, not your phone's. Pick
**US QWERTY** or **Turkish Q** in the Keyboard screen to match whatever
your computer is actually set to. The Turkish-Q mapping was built from
Microsoft's own published KBDTUQ.DLL scancode table, not guessed. AltGr-
level symbols aren't supported yet — letters, digits, Turkish characters,
space, and enter all work.

## Notes / limitations

- Uses Android's standard `BluetoothHidDevice` API (classic Bluetooth) —
  no hidden APIs, no root.
- The mouse report descriptor includes a scroll wheel axis; the keyboard
  report is a standard 6-key + modifier layout.
- Stealth is scoped to how the device appears to the **connected
  computer** (name, device type) — not to anything on the phone itself
  (app name, icon, notification wording), which is unchanged from
  earlier and intentionally so per how this app is meant to be used.
