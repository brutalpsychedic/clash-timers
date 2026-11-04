# Clash Timers

Android home-screen widget for Clash of Clans upgrade countdowns. It shows builders (plus the Goblin Builder), lab (plus the Goblin Researcher), heroes, pets and Builder Base. Each row has a live countdown and a progress bar. The header shows your name, Town Hall level and a Sync button.

<img src="docs/widget-preview.png" alt="Clash Timers widget showing builder, lab, pet and Builder Base countdowns" width="360">

Supercell's public API doesn't expose upgrade timers, so the widget reads the game's own **Data Export** (Settings › More Settings › Data Export › Copy). That export is a snapshot: re-export whenever you start, finish or speed up something.

## Install (sideload)

1. Build the APK (see [Rebuilding](#rebuilding)) and copy it to your phone (USB, Quick Share, Drive…).
2. Open it. Allow "Install unknown apps" for whichever app opened it. If Play Protect warns about an unknown developer, choose **Install anyway**.
3. Open **Clash Timers**, type your in-game name and tap **Save**. The export has your tag but not your name.
4. In Clash: **Settings › More Settings › Data Export › Copy**. Back in Clash Timers, tap **Sync from clipboard**.
5. Tap **Add widget to home screen**, or long-press the home screen › Widgets › Clash Timers. Resize it to taste; the list scrolls.
6. Android 14+: tap **Allow exact countdowns** when it appears. Without it, timers longer than a day show `2d 14h` instead of ticking `2d 14:08:52`.

## Daily use

- **Sync** on the widget reads the clipboard. If no new export is there, it opens Clash so you can copy one, then tap Sync again.
- Tapping any row opens Clash.
- **Potions**: the export doesn't say when you drink one, so log it in the app (Builder ×10, Research ×24, Pet ×24, 1 hour from now). The builder header shows a `×10 34:44` pill while it runs.
- `💤 N builders idle`, `Laboratory idle` and `Pet House idle` rows appear when something isn't working.
- The footer shows when the export was taken, plus `· N done` once upgrades finish (time to collect and re-sync).

## Real icons (optional)

The rows use built-in placeholder glyphs, coloured by category. To use real artwork, put PNG/WebP files named by the export's data id into `app/src/main/assets/icons/` (e.g. `1000019.png` for Hidden Tesla, `28000000.png` for Barbarian King) and rebuild. Ids are listed in `app/src/main/assets/gamedata.json`. Supercell's Fan Content Policy covers personal fan use of game art.

## Rebuilding

Open this folder in Android Studio and use Build › Build APK(s), or with JDK 17 and the Android SDK:

```
gradlew assembleRelease     # -> app/build/outputs/apk/release/app-release.apk
gradlew testReleaseUnitTest # parser/potion tests + renders app/build/preview/widget.png
```

Signing: if `keystore/clashtimers.jks` exists (it's git-ignored), builds are signed with it. A new APK then installs over the old one and keeps your settings. Without it, Gradle falls back to the Android debug key, and switching keys means uninstalling first. To make your own key:

```
keytool -genkeypair -keystore keystore/clashtimers.jks -alias clashtimers -keyalg RSA -keysize 2048 -validity 10000 -storepass clashtimers -keypass clashtimers -dname "CN=Clash Timers"
```

## Known limits

- **Upgrade durations** (for progress bars) come from a ClashKingAssets snapshot dated 2026-10-12. Anything added to the game later shows as `Item #id`, and its bar starts from the first sync. Regenerate `gamedata.json` to update it.
- **Potions** assume the export reports un-boosted time remaining. The builder potion is also applied to the Goblin Builder. Clock Tower, Builder's Apprentice and Lab Assistant boosts aren't modelled, so re-sync after using them.
- **Town Hall weapon vs. Town Hall upgrade**: a running Town Hall timer counts as a weapon upgrade when the weapon is below level 5. Otherwise it counts as the Town Hall itself.
- **Supercharges** show as `→ ⚡N` with a bar measured from the first sync.
- Without exact-alarm permission, a countdown that hits zero can show a minus sign for a few minutes until Android lets the widget refresh.
