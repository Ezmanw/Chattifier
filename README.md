# Chattifier

A native Material 3 Expressive Android app for running AI models fully on your phone.

- **Chat** with on-device models, with photos, audio and video for models that support them
- **CPU / GPU / NPU switcher**: LiteRT-LM on Adreno GPU or the Hexagon NPU (Snapdragon 8 Elite), llama.cpp (GGUF) on CPU or Adreno via OpenCL
- **Voice mode** with M3 shape morphing: speech-to-speech models (LFM2.5-Audio) talk back in their own voice; other models use Kokoro neural TTS
- **Hugging Face downloads** with resume, plus bundles (GGUF + encoders, speech models)
- **Code mode**: a background coding agent with a built-in Alpine Linux shell (PRoot), permission modes (Ask / Allow edits / Always allow), notification approvals, persistent sessions, and the ability to send it notes while it's still working
- **Phone control**: over the same wireless-debugging link the agent can see your screen, take screenshots, tap, swipe, type and launch apps — so it can drive your phone (e.g. do your homework in an app) with no PC
- **Wear OS**: ask from your watch by voice or keyboard, or from a watch-face complication. The watch sends the question to the phone over Bluetooth (Wear OS Data Layer), the phone's local model answers, and the reply comes back as text and speech (Kokoro rendered on the phone, or the watch's own voice). Works with no mobile data or Wi‑Fi
- **Background activation**: Shizuku-style pairing with the phone's own wireless debugging to lift battery and background limits

## Build

```bash
export JAVA_HOME=<JDK 17+>
export ANDROID_HOME=<Android SDK>
./gradlew :app:assembleDebug
./gradlew :wear:assembleDebug   # the watch app; install both from the same machine so they share a signing key
```

Native pieces under `app/src/main/jniLibs` come from Termux (PRoot, `scripts/fetch-proot.sh`),
an upstream llama.cpp build (`llama-server` with OpenCL), Liquid AI's LFM2.5-Audio runner, and
sherpa-onnx. Licences are in `app/src/main/assets/licenses`.
