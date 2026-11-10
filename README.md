# Sol Macro (Android)

Fishing and selling helper for Sol's RNG. Not affiliated with Roblox. Macros can break game rules and get accounts banned.

## Included (all untested on a real phone until you test them)
- Floating round icon button (the Brry logo by default). Tap it to open the control bar (START/STOP, PAUSE/RESUME, SET, COLOR, TEST), tap it again to close it. Drag it to move it. "Use This Icon" in the app lets you pick your own image.
- Fishing by timing, or by screen detection (green zone + white marker, hold when the marker drifts).
- Fast capture with MediaProjection (about 30 checks per second) with a screenshot fallback (about 3 per second, Android 11+).
- Debug overlay: green, yellow and red box with a match percentage, zone edges and marker line.
- Movement, camera and Shift Lock scripts using swipes and taps on Roblox's on-screen controls.
- Auto sell with scripts to walk to the seller and back.
- Failsafe: stops after 3 casts where the bar is never seen.
- Log with a Copy button, Discord webhook, status card with checks per second and latency.

## Not included
- OpenCV template matching, OCR, trained AI recognition, automatic seller detection, graphs for earnings, CPU or battery.

## Build
Upload every file to a GitHub repository (keep folders, including .github/workflows). The Actions tab builds app-debug.apk.
