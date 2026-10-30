CLEAN TRANSCRIBER (floating mic for any text box, keeps your normal keyboard)

SET UP ON THE PHONE
1. Open Clean Transcriber. Tap "Allow microphone".
2. Tap "Turn on in Accessibility settings", find Clean Transcriber (it may be
   under Installed apps) and switch it on.
   If it is greyed out or says restricted: tap "Open App info", tap the three
   dots at the top right, choose "Allow restricted settings", then try again.
3. Tap any text box in any app. A mic button appears at the right edge.
   Tap it, talk, tap it again to stop. Drag it to move it.

UPDATING THE GITHUB REPO FROM A CODESPACE
  rm -rf app
  unzip -o CleanTranscriber-v2.zip
  cp -r CleanTranscriber/. .
  rm -rf CleanTranscriber CleanTranscriber-v2.zip
  git add -A
  git commit -m "Accessibility version"
  git push origin HEAD:main
Then open the Actions tab, wait for Build APK to finish, and download the new APK.
