# Play Store Assets

## App Icon

Required:

- Play Store icon: `512 x 512`
- Android launcher adaptive icon

Visual concept:

- Dark grid
- Glowing hidden target pulse
- Two echo rings
- Clean high-contrast puzzle/audio identity
- Avoid text in the icon

Current status:

- The repository has a simple vector launcher icon.
- Create final EchoGrid launcher icon before open testing.

## Screenshots

Capture phone screenshots showing:

1. Early easy grid
2. Heat trail after several guesses
3. Near-target strong feedback state
4. Target found / scoring state
5. Level progression / larger grid if available

Screenshots may be captured manually from a physical device or emulator.

Suggested folder:

```text
docs/play/screenshots/
```

## Feature Graphic

Required size:

```text
1024 x 500
```

Guidance:

- No tiny text
- Communicate "audio puzzle / hidden target / echo grid"
- Use the same dark grid, echo ring, and hidden target pulse language as the icon

## Optional Promo Copy

- Find the hidden signal.
- Every tap is an echo.
- Navigate by pitch, rhythm, and vibration.

## Screenshot Capture Workflow

Create the screenshot folder:

```bash
mkdir -p docs/play/screenshots
```

Install the debug build:

```bash
./gradlew :app:installDebug
```

Capture a screenshot from an attached Android device or emulator:

```bash
adb exec-out screencap -p > docs/play/screenshots/phone-01.png
```

Repeat the capture for each gameplay state needed for the Play listing.
