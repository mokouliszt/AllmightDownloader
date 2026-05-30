# AllmightDownloader (Ionic React + Capacitor)

<img width="150" height="150" alt="AllmightDownloader" src="https://github.com/user-attachments/assets/a9c32b89-ec01-4568-844d-3259747fa503" />

An Android app that overlays a floating button on top of other apps and saves videos/audio with yt-dlp via a centered URL input overlay.

- **part (a)**: A red circular "+" floating button in the top-left → tap to open a URL input panel centered on screen (with a dimmed scrim around it and a +/− toggle).
  Drag the button → drop it on the trash icon (red outline) at the top-center to terminate.
- **part (b)**: The panel's "Save MP4 (HD)" / "Save MP3" buttons → download via `youtubedl-android` (which bundles yt-dlp + Python + ffmpeg).
  Progress (percentage, ETA, cancel) is shown in the notification bar, and files are saved to `Download/` on completion.

## Tested Sites

- youtube ... stable
- instagram ... stable
- X ... partially unstable
- tiktok ... partially unstable

## Structure

```
AllmightDownloader/
├─ src/                         … Ionic React (home: permissions / start-stop only)
│  ├─ App.tsx, overlay.ts, main.tsx, theme.css (primary red override)
├─ android/app/src/main/
│  ├─ java/com/example/allmightdownloader/
│  │  ├─ MainActivity.java          … plugin registration + notification permission (13+) request
│  │  ├─ overlay/OverlayPlugin.java  … permission / start / stop / isRunning
│  │  ├─ overlay/OverlayService.java … floating button + centered panel + trash. Pressing a button launches DownloadService
│  │  └─ download/DownloadService.java … runs yt-dlp + progress notification + saves to Download/ (dataSync FGS)
│  └─ AndroidManifest.xml
├─ capacitor.config.ts          … appId: com.example.allmightdownloader
└─ package.json
```

## Download Flow (part b)

1. Enter URL in the panel and tap MP4 / MP3 → OverlayService.onSave() launches DownloadService and closes the panel
2. DownloadService (foreground, dataSync) calls YoutubeDL/FFmpeg.init() on first run only (extracts python/ffmpeg)
3. Builds a YoutubeDLRequest and runs execute(request, processId, callback). The callback is a Kotlin Function3<Float,Long,String,Unit>
   - MP4 (HD): -f bestvideo*+bestaudio/best + --merge-output-format mp4 (merged via ffmpeg)
   - MP3: -x --audio-format mp3 --audio-quality 0
4. Progress is shown in the notification bar (setProgress + "Cancel" action = destroyProcessById)
5. On completion, the downloaded file is moved to Download/ via MediaStore (Android 10+). The completion notification shows the save location.

## Build

Prerequisites: Node 18+ / JDK 17 or 21 / Android SDK (platform 35, build-tools 35).

```bash
npm install
npm run build
npx cap sync android
cd android
./gradlew assembleDebug   # → app/build/outputs/apk/debug/app-debug.apk
```

Set sdk.dir=... in android/local.properties (or the ANDROID_HOME environment variable).
