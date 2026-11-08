# Farm Sync: design plan (v1.0 look)

Status: planning, for approval. Date: 2026-11-08.
Method: apple-design skill (HIG principles, accessibility, color, typography, layout, branding, app icons, lists, dark mode, feedback, toggles), translated to Android / Jetpack Compose / Material 3. Look-and-feel reference: the Stardew Valley wiki (colours and mood only; **no assets, logos, fonts or artwork copied**).

---

## 1. Review of the current app (summary)

**Rating: Needs work.** The app is safe and functional, but it shows how it's built rather than what you care about. Its thesis should be: *"Is my farm safe and the same on all my devices?"* Today no element answers that at a glance.

| # | Finding | Severity | Guideline |
|---|---|---|---|
| 1 | Internal names are shown everywhere: `Farm_123456789`, file lists with byte sizes, hash prefixes (`50262331`), "Shizuku listo", "versionCode". | High | `writing.md`: names come from what people recognise; `layout.md › Visual hierarchy`: "Use progressive disclosure" |
| 2 | No visual hierarchy: every card is an equal grey box with 5–7 lines of small text, and the in-game date (the thing you recognise a save by) is just a line of body text. | High | `layout.md`: "Order content by relative importance" |
| 3 | Actions are text buttons in the top bar ("Importar", "Copiar todas"); back is a "←" text button. | Medium | `branding.md`: "Express your brand with familiar components"; Android: icon buttons + overflow menu |
| 4 | Status is told by long sentences in snackbars and cards rather than shown in place. | Medium | `feedback.md`: "Consider integrating status feedback into your interface" |
| 5 | Dynamic system colours give it no identity at all: it could be any app. | Medium (craft) | `branding.md`; craft lens: no point of view |
| 6 | The switch row is fine. The sync state is text-only (✓/↑/⚠ glyphs), which is OK for colour-blind users and should stay. | — (keep) | `color.md › Inclusive color` |

---

## 2. Design direction: "Valley sky"

**Grounded in the subject:** a farming-life game played on a phone and a tablet. The wiki's mood is a **sky-blue panel over a valley**, **meadow-green headers**, and warm **wood/harvest** accents. We take that *mood*, not its pixels. The palette is our own and every pair is checked for contrast.

### Colour tokens (light / dark), contrast measured

| Token | Light | Dark | Role | Contrast |
|---|---|---|---|---|
| `sky` (background) | `#EEF7FA` | `#0F0D2A` (night sky) | Page | – |
| `card` | `#FFFFFF` | `#1B2140` | Cards, sheets | – |
| `ink` (text) | `#1C2B33` | `#E8F1F4` | Primary text | 14.6:1 on card / 13.7:1 |
| `ink-2` | `#4A5E66` | `#A9B8C4` | Secondary text | 6.8:1 / 7.7:1 |
| `meadow` (accent) | `#1F7A33` | `#7BD88A` | Primary actions, "in sync" | 5.4:1 white-on / 10.8:1 night-on |
| `harvest` | `#F4C04A` | `#F4C04A` | Signature highlight (date tile, attention badge), always with dark text | 8.7:1 ink-on |
| `bark` | `#7A4B26` | `#D9A77A` | Wood details, origin icons | 7.3:1 |
| `alert` | `#B3261E` | `#FFB4AB` | Errors only | 6.5:1 / 9.2:1 |

Season tints for the date tile, each with `ink` text (all ≥ 9.5:1): spring `#CDEFC4` · summer `#FCE7A6` · fall `#F6C9A0` · winter `#D3E6F7`.

The wiki's bright green `#03A007` with white text is only **3.48:1**, so we use the deeper `meadow` instead.

**Colour rules** (`color.md`):
- One colour, one meaning: meadow = in sync / primary action, harvest = needs a look, alert = error.
- Every state also carries an icon and a word.
- The app follows the system light/dark setting, with no app-specific switch (`dark-mode.md`).

### Typography

| Use | Face | Size / weight |
|---|---|---|
| Farm names, screen titles, date tile number | **Nunito** (rounded, friendly; SIL Open Font License, bundled) | 22–28 sp, Bold |
| Body, labels, buttons | **Roboto** (system) | 16 sp body, 14 sp secondary, 12 sp captions (minimum) |

- **Brand vs. readability:** brand lives in the display text; body stays the system face for legibility (`branding.md`: "custom font for headlines … system fonts for body").
- **Text scaling:** everything scales with the system font size (`sp`), and layouts are checked at 200 %.
- **Weights:** no thin weights.

### Signature element: the **day tile**

Each farm is recognised by *its in-game day*, so that becomes the hero:

```
┌──────┐
│  24  │  ← Nunito Bold 28, ink
│verano│  ← 12 sp, season-tinted tile
│ año 1│
└──────┘
```

It's a small calendar-page tile, tinted by season, shown on every farm row and large in the detail header. It's the one thing the app will be remembered by, and it belongs to *this* product. Everything around it stays quiet.

### Layout: farm list (compact width)

```
┌──────────────────────────────────┐
│ 🌱 Farm Sync              ⟳   ⋮  │  large top app bar; ⋮ = Importar, Copia de todas, Ajustes
├──────────────────────────────────┤
│ ✓ Todo sincronizado · hace 2 min │  sync status pill (meadow) – tap → details
│                                  │
│ Tus granjas                      │
│ ┌──────┐ La Maria             ›  │
│ │  24  │ Emma · 13 h 47 min      │
│ │verano│ ✓ Sincronizada          │
│ └──────┘                         │
│ ┌──────┐ Certeza              ›  │
│ │  19  │ Mia · 4 h 15 min        │
│ │prima.│ ↑ Subida hace 1 min     │
│ └──────┘                         │
│                                  │
│ En Google Drive                  │  only when something is offered
│ ...                              │
└──────────────────────────────────┘
```

**Problems show up in place of the status pill**, as one card with one action:
- Shizuku off → "Conéctate a tu Wi-Fi y activa la Depuración inalámbrica" + [Abrir ajustes].
- Stardew open → "Cierra Stardew para descargar".

The technical status card (Shizuku / game version) disappears from the main screen and moves to **Ajustes → Diagnóstico**.

**Regular width (tablet):** a two-column list/detail layout (Compose `ListDetailPaneScaffold`).

### Layout: farm detail

```
┌──────────────────────────────────┐
│ ←  La Maria                   ⋮  │  ⋮ = Compartir, Detalles técnicos
├──────────────────────────────────┤
│ ┌────────┐  La Maria             │
│ │   24   │  Emma                 │
│ │ verano │  13 h 47 min jugadas  │
│ │ año 1  │                       │
│ └────────┘                       │
│ ┌──────────────────────────────┐ │
│ │ Sincronizar con Drive   [●═] │ │
│ │ ✓ Al día · hoy 16:12         │ │
│ └──────────────────────────────┘ │
│ Copias de seguridad              │
│ ◷ Hoy, 16:12   24 de verano    ⋮ │  ⋮ = Restaurar, Fijar, Compartir
│ ☁ Hoy, 15:56   24 de verano    ⋮ │
│ ✋ Hoy, 13:58   23 de verano 📌 ⋮ │
│ ▸ Detalles técnicos              │  collapsed: folder, files, version
│                  [ + Crear copia ]│  extended FAB
└──────────────────────────────────┘
```

### Writing changes (sentence case, verbs first, no jargon)

| Now | New |
|---|---|
| "Shizuku listo" / "Stardew Valley 1.6.15.3 instalado" | (moved to Diagnóstico) |
| "Última copia: 8/11/26 1:58 p. m. (3 en total)" | "Copia de hoy, 13:58" |
| "Copiar todas" (top bar) | ⋮ → "Crear copia de todas" |
| "Sincronización terminada: 1 subidas, 0 descargadas, …" | "1 granja subida" (only what happened) |
| `Farm_123456789` on cards | hidden; shown in "Detalles técnicos" |
| origin "automática / antes de restaurar" | an icon (◷ auto, ✋ manual, ☁ sync, ↺ before restore) plus the text for screen readers |

### Motion

One moment only: the ⟳ in the status pill **turns while syncing**, and when done it settles into a ✓ with a short scale pop (150 ms). It's skipped when the system's *remove animations* setting is on (`motion.md`).

---

## 3. App icon (own design, no game assets)

`design/icon/ic_launcher.svg`:
- **Background:** a soft dawn **sky** and a rounded **green hill**.
- **Foreground:** a **sprout** (two leaves) growing from a small mound of **soil**, framed by two **harvest-gold sync arrows** forming a circle. It reads as "your farm, kept in sync".

How it follows the icon guidelines:
- **Shapes:** a few filled, overlapping shapes, no text, no fine lines, centred in the 72 dp safe zone of the 108 dp Android adaptive canvas (`app-icons.md › Design`, `› Icon shape`).
- **Themed icon:** a monochrome layer (sprout + arrows) for Android 13+ themed icons, with the same features in every appearance (`app-icons.md › Appearances`).

---

## 3b. Pixel-art direction (user request, 2026-11-08), which supersedes the flat look above

The user asked for a pixel-art style. Same tokens, layout and signature; what changes is the visual language:

| Element | Pixel version | Source and licence |
|---|---|---|
| Display type (screen titles, farm names, day number, section headers) | **Pixelify Sans** 700, at 19 sp or larger | Google Fonts, SIL OFL, bundled |
| Body, labels, buttons | Roboto (system), unchanged, for legibility at small sizes | system |
| UI icons | **Pixelarticons** 2.4.2: check, cloud, upload, download, clock, human, bookmark, reload, more-vertical, arrow-left, chevron-right, plus, pause | MIT licence, imported as vector drawables |
| Cards, pill, buttons, switch, FAB | **Pixel frames**: 4 dp border in wood brown (light) / dusk violet (dark), stepped corners (clip-path / custom `Shape`), 1-step bevel (highlight top, shade bottom) | own |
| Day tile | Pixel-framed, season-tinted, pixel numerals | own |
| App icon | **Pixel art on a 32×32 grid**, generated from geometric shapes by `design/pixel_icon.py`. Normal, round and monochrome (themed) variants; everything inside the adaptive-icon safe zone. | own |
| Decoration | One thin pixel hill strip at the bottom of the farm list. Nothing else decorative. | own |

**What we deliberately don't do (IP):** no game sprites, characters, items, UI frames, logo or game font. Pixel art is a style, not an asset; ours is built from our own shapes and palette.

**Accessibility kept:**
- **Pixel font:** only for display text at 19 sp or more.
- **Contrast:** all pairs from section 2 are unchanged.
- **Icons:** each state still carries an icon and a word.
- **Hidden decoration:** pixel frames and the hill are decorative and hidden from TalkBack.
- **Screen readers:** the switch keeps `role=switch`.

Mockup: `design/mockup_pixel.html` (generated from `mockup_pixel.template.html`). Icon: `design/icon/ic_launcher_pixel*.svg`.

## 4. Implementation sequence (after approval)

1. **Theme:** `FarmSyncTheme` with the tokens above (light/dark `ColorScheme` plus custom colours for season and harvest), Nunito bundled, `Typography` scale.
2. **Icon:** adaptive icon (foreground/background vector drawables), monochrome layer, round variant.
3. **Farm list:** large top app bar with ⟳ and ⋮, status pill, in-place problem card, farm rows with day tile and status line, "En Google Drive" section.
4. **Detail:** hero day tile, sync card, backup list with origin icons and ⋮ menu, collapsible "Detalles técnicos", extended FAB.
5. **Ajustes / Diagnóstico** screen (Shizuku, game version, Drive account, sync log, snapshot retention). This also covers the open M5 items.
6. **Checks:** Spanish and English, dark mode, 200 % font, TalkBack labels, tablet two-pane.

**Not changed:** all safety logic, sync rules and data. This is purely presentation.
