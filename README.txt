CLEAN TRANSCRIBER (Android dictation keyboard)

BUILD OPTION A: Android Studio (on a PC)
1. Unzip this folder.
2. Android Studio > Open > select the CleanTranscriber folder. Let Gradle sync.
3. Build > Build APK(s). The file lands in app/build/outputs/apk/debug/app-debug.apk
4. Copy the APK to your phone and open it. Allow "install unknown apps" if asked.

BUILD OPTION B: GitHub (no Android Studio)
1. Make a free GitHub account and a new repository.
2. Upload every file in this folder, keeping the folder structure
   (including the hidden .github folder). The "Add file > Upload files" page
   accepts a dragged folder on a computer.
3. Open the Actions tab > Build APK > Run workflow. When it finishes, download
   the CleanTranscriber-apk artifact, unzip it and install app-debug.apk.

SET UP ON THE PHONE
1. Open Clean Transcriber. Tap "Allow microphone".
2. Tap "Enable the keyboard" and switch Clean Transcriber on.
3. Tap "Switch to Clean Transcriber".
4. In any app, tap a text box, tap the mic, talk, tap the mic again to stop.
