# JKPHermex Design System

JKPHermex is a professional native control surface for a self-hosted AI agent.
It should feel calm, fast, information-dense, and trustworthy. The iOS client's
clarity is the cross-platform quality baseline; Android uses native Compose and
Material interaction behavior rather than imitating iOS controls pixel for
pixel.

## Product principles

1. **Conversation first.** Chat content and the composer receive the most
   space. Navigation stays available without competing with the conversation.
2. **One obvious action.** Never stack duplicate back, menu, jump, or refresh
   controls. A control's icon, label, and behavior remain consistent everywhere.
3. **Quiet confidence.** Gold signals brand and active state; it does not fill
   every control. Neutral surfaces carry structure.
4. **Honest state.** Loading, cached, offline, authentication, and empty states
   are visually distinct and never contradict each other.
5. **Native accessibility.** Every action has a 48dp interaction target, a
   meaningful semantic label, predictable focus order, and non-color status cue.

## Color

- Canvas: true black in dark mode; white in light mode.
- Primary surface: system gray 6 equivalent for cards, composer, and sheets.
- Secondary surface: system gray 5 equivalent for selected or nested regions.
- Primary text: maximum readable contrast against the canvas.
- Secondary text: at least 4.5:1 for normal-size copy.
- Accent: JKP gold. Reserve it for active selection, primary actions, progress,
  and the scrollbar thumb. Large gold fills require dark readable foreground.
- Destructive, warning, and success states use both color and text/icon meaning.

## Typography

- Use the platform sans-serif for product UI and monospace only for code,
  paths, commands, and the compact wordmark.
- Screen title: 22sp bold maximum.
- Section title: 18sp semibold.
- Body: 16sp regular with comfortable line height.
- Supporting metadata: 13-14sp; never rely on sub-12sp text.
- Truncate only secondary metadata. Titles receive one or two lines based on
  context and expose their complete value to accessibility services.

## Spacing and shape

- Base spacing unit: 4dp.
- Screen gutters: 16dp; dense list content may use 12dp internally.
- Standard gaps: 4, 8, 12, 16, 24, and 32dp only.
- Touch targets: minimum 48x48dp. Icons are normally 20-24dp inside the target.
- Corners: 12dp for controls and small cards, 16dp for content cards, 22dp for
  composer/sheets. Circular treatment is reserved for icon-only actions.
- Prefer spacing and subtle tonal contrast to borders and shadows.

## Navigation

- Phone chat uses one leading menu button integrated into the header. The
  drawer contains New chat, Sessions, Notes, Prompts, and Settings.
- System Back returns to Sessions when no modal, drawer, or sheet is open.
- Tablet keeps a persistent session rail and uses Back only for nested content.
- No floating navigation control may overlap a header action.

## Chat

- Header is compact and stable while content scrolls.
- Header actions use Material vector icons with semantic labels; emoji are
  content, never navigation chrome.
- User messages use a contained neutral bubble. Assistant responses sit on the
  canvas with structured thinking/tool/code surfaces.
- Composer remains fully above system navigation and IME insets. Secondary
  options may scroll horizontally but the send/stop action never clips.
- Jump controls appear only when they have a distinct destination. The
  scrollbar remains a direct-manipulation overview, not a decorative rail.

## Fast scrollbar

- 48dp-wide gesture target, 5dp idle thumb, 8dp active thumb.
- Thumb uses the accent at full opacity. Track uses the accent at 24-30% opacity
  so thumb position remains immediately legible.
- Pixel-based position handles variable-height chat messages; endpoints snap
  exactly to top and bottom.
- Tap and drag use the same content-height model as rendering.
- Hide when content fits. Otherwise remain visible long enough to support
  reading and direct manipulation without appearing broken.
- Expose progress semantics and a descriptive action label to TalkBack.

## Motion

- Standard transitions: 160-220ms ease-out.
- Motion explains navigation or state change; it never delays input.
- Respect the system animator-duration scale. With animations disabled, state
  changes are immediate.

## Quality gate

Every changed screen must be checked in dark and light theme, portrait phone
and 600dp+ layout, font scale 1.0 and 1.3, TalkBack semantics, keyboard/IME open,
loading/error/empty/content states, and top/middle/bottom scroll positions.
