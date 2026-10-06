# It's My Rime UI

Reference: [Claude DESIGN.md by VoltAgent](https://github.com/VoltAgent/awesome-design-md/blob/main/design-md/claude/DESIGN.md).

This Android adaptation uses warm cream, coral actions, warm ink and dark product surfaces. It keeps the app's own name and keyboard identity.

- All colours come from `UiTheme.kt`, shared by Compose settings and native keyboard panels.
- Light canvas: `#FAF9F5`; cream surfaces: `#F5F0E8` / `#EFE9DE`; dark canvas: `#181715`.
- Signature coral: `#CC785C`. Small filled actions use the darker coral `#A9583E` so their light labels remain readable.
- Display headings, the keyboard toolbar brand caption and keyboard characters use bundled Cormorant Garamond 500 for Latin and Noto Serif TC 400 for Chinese. Key hints and character choice menus share that face. Body text uses Inter / Noto Sans TC at 400; controls and candidates use 500. Shortcut codes keep system monospace, and Emoji keep the system colour font.
- Latin and Chinese are merged into renamed static font files so Android 9 can render the same typography as later versions. The sans files preserve the source fonts' complete character coverage; fixed headings and keyboard text use a serif subset that includes full-width punctuation and superscript/subscript digits. Unsupported characters can still fall back to system fonts.
- Font sources, licenses and hashes are recorded in `app/src/main/assets/fonts`. Regenerate with `tools/generate_ui_fonts.py` after adding fixed headings or keyboard characters. Normal Gradle builds use the checked-in fonts and never download them.
- Settings spacing: 24 dp between sections, 12 dp between a section heading and its content, 16 dp inside ordinary cards, 20 dp inside the engine card.
- Shapes: 8 dp buttons, keys and inputs; 12 dp cards and selection menus. Shadows are reserved for floating menus and the composition preview.
- Settings respect status, navigation and keyboard insets. Content caps at 640 dp on wider screens.
- Keyboard height, letter positions and gesture hit areas stay stable. Toolbar actions share the existing 44 × 40 dp size. Adjacent action groups have explicit gutters.
- Key corner hints use one-line text with intrinsic height and no extra font padding. They align to the key's existing top inset and a 4 dp right margin, avoiding fixed-height clipping with bundled fonts.
- Emoji categories remain flat with filled selected icons. Candidates remain flat. Clipboard and media items use warm surfaces and restrained icons.
- Both appearance modes cover settings, keys, candidates, clipboard, Emoji, GIF/MyGO search and floating previews.
- The app mark is a cream serif R with an insertion cursor on coral. Adaptive launcher layers support round/square masks and Android 13 themed icons; editable artwork is in `artwork/app-icon.svg`.
- The MyGO menu uses a single-colour silhouette based on the supplied band wordmark. It follows the same selected and unselected colours as other menu options; source artwork is in `artwork/mygo-mark.svg`.
