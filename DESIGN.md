# It's My Rime UI

Reference: [Claude DESIGN.md by VoltAgent](https://github.com/VoltAgent/awesome-design-md/blob/main/design-md/claude/DESIGN.md).

This Android adaptation uses warm cream, coral actions, warm ink and dark product surfaces. It keeps the app's own name and keyboard identity.

- All colours come from `UiTheme.kt`, shared by Compose settings and native keyboard panels.
- Light canvas: `#FAF9F5`; cream surfaces: `#F5F0E8` / `#EFE9DE`; dark canvas: `#181715`.
- Signature coral: `#CC785C`. Small filled actions use the darker coral `#A9583E` so their light labels remain readable.
- Display and section headings use the Android serif fallback at regular weight. Controls and body text use system sans; shortcut codes use monospace.
- Settings spacing: 24 dp between sections, 12 dp between a section heading and its content, 16 dp inside ordinary cards, 20 dp inside the engine card.
- Shapes: 8 dp buttons, keys and inputs; 12 dp cards and selection menus. Shadows are reserved for floating menus and the composition preview.
- Settings respect status, navigation and keyboard insets. Content caps at 640 dp on wider screens.
- Keyboard height, letter positions and gesture hit areas stay stable. Toolbar actions share the existing 44 × 40 dp size. Adjacent action groups have explicit gutters.
- Emoji categories remain flat with filled selected icons. Candidates remain flat. Clipboard and media items use warm surfaces and restrained icons.
- Both appearance modes cover settings, keys, candidates, clipboard, Emoji, GIF/MyGO search and floating previews.
