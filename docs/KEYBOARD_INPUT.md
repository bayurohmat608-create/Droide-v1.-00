# Editor and terminal keyboard modes

Select **Settings > User > Keyboard & Input > Editor & terminal keyboard**. The same selector is available under **Terminal**. This is one user preference, shared across workspaces and terminal backends.

| Mode | Android input metadata | Intended behavior |
| --- | --- | --- |
| Compact (default) | `TYPE_NULL` | Request raw keyboard input without word suggestions/corrections and request a compact layout without the extra number row. |
| No corrections | `TYPE_CLASS_TEXT`, normal variation, `TYPE_TEXT_FLAG_NO_SUGGESTIONS` | Retain composed language input without word suggestions/corrections. |
| Normal | `TYPE_CLASS_TEXT`, normal variation, `TYPE_TEXT_FLAG_AUTO_CORRECT` | Allow the keyboard's normal word suggestions/corrections. |

Compact is the default for new installations, upgrades without this preference, and unknown preference values. Explicit choices restore from the user DataStore key `coding_keyboard_mode`; workspace configuration is not modified. Preference IDs are `compact`, `no_corrections`, and `normal`.

Android does not provide a universal API to force an external keyboard's number row off. Compact requests a raw input profile; the keyboard decides its layout and may retain a user-enabled number row. If that happens, its number-row preference must also be turned off in that keyboard's settings. If Compact interferes with composed language input, choose No corrections. These are input profiles, not a replacement keyboard or global keyboard configuration.

## Integration

- `DroideCodeEditor` keeps Sora's valid text input type and real input connection. Only the metadata sent to the keyboard is adjusted. Review mode, selection, smart typing, code completion, and language-server completion keep their existing paths.
- `DroideNativeTerminalView` subclasses the pinned Termux view and returns its original input connection. The vendored AARs, source archives, provenance, and native libraries are untouched. The client no longer requests Termux's legacy input-class workaround; the wrapper applies the selected valid profile.
- `DeviceTerminalView` retains its original composing/commit, deletion, raw-key, and Unicode handlers.
- The fallback command field uses Compose's scoped `InterceptPlatformTextInput`, available since Compose UI 1.7.0. It retains the original input connection, command text state, and Send action. The interceptor is remembered by mode, preventing unrelated recompositions from restarting input.
- A view's active keyboard is restarted only when the mode changes, with pending composition finished first. Unfocused views use the new mode on their next input connection.
- All coding profiles request no fullscreen/extracted editor and no personalized keyboard learning. Existing action bits are retained.
- Agent conversation inputs, file-name fields, search fields, and other forms are outside this policy. Droide's accessory-key row remains independently configurable. Editor tabs remain 40 dp.

Settings rows use one selectable radio action per row with a minimum 48 dp target. Writes are serialized by disabling the selector during saving. Save failures appear in the Settings snackbar; the selected mode is driven by persisted preference state.

No dependency or runtime payload was added. The small production preference enum is plain Java and the adapters use existing Android, Compose, and DataStore APIs.

## Verification

Run host preference checks without building Android artifacts:

```bash
python3 tools/verify_coding_keyboard.py
python3 tools/build-readiness/prepare_build.py source
bash tools/verify_release_source.sh
```

The first command compiles the actual production preference policy with JDK 17 and runs 25 assertions covering defaults, migration/fallback, stable unique IDs, restoration, and profile invariants. This does not compile the Kotlin Android adapters or certify an external keyboard's behavior.

In a provisioned Android build environment, compile the adapters and run existing tests without assembling an APK:

```bash
./gradlew :app:compileDebugKotlin :app:compileDebugJavaWithJavac :app:testDebugUnitTest
```

Physical acceptance checks:

1. On a new install and an upgrade without this preference, confirm Compact is selected. Select each mode, reopen Settings, restart Droide, and switch projects; confirm the saved selection.
2. Check editor, local PTY, Device Workstation terminal, and fallback terminal with the installed Gboard, Samsung Keyboard, or other keyboard. Compare number row, suggestions, corrections, and landscape fullscreen behavior. Record keyboard version, language, Android version, and global number-row preference.
3. Type code with underscores, spaces, quotes, punctuation, and digits. Verify Enter, Backspace, forward delete, paste, selection, emoji, Tab, Ctrl+C, and Ctrl+Space. Verify shell commands are delivered once and code completion still works.
4. Test composed language input in No corrections and Normal. If Compact is incompatible with an IME's composing behavior, the fallback must remain usable.
5. Change mode while input is active; verify pending text is retained and characters do not duplicate. Check Agent and ordinary forms continue using their normal keyboard profiles.

## Primary references

- [Android InputType](https://developer.android.com/reference/android/text/InputType)
- [Android EditorInfo](https://developer.android.com/reference/android/view/inputmethod/EditorInfo)
- [Compose InterceptPlatformTextInput](https://developer.android.com/reference/kotlin/androidx/compose/ui/platform/InterceptPlatformTextInput.composable)
- [Termux TerminalView v0.118.0](https://github.com/termux/termux-app/blob/v0.118.0/terminal-view/src/main/java/com/termux/view/TerminalView.java)
