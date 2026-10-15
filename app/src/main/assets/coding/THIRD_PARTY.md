# Bundled coding and media sources

Exact coding sources and dependency requirements are recorded in packs.json.
Spec Kit templates: github/spec-kit at 3238fa9285694dc539aeb9829b337bfd6aed7317 (MIT); LICENSE alongside templates.
Superpowers reference instructions: obra/superpowers at bb92a77741419a4ab5f06e711a283343f1ada0c3 (MIT); LICENSE alongside skill references.
Whisper engine: ggml-org/whisper.cpp at 0c7be696371069e0baa2515ed7aa08d5a5473aa6 (MIT); fetched from official source during build.
Optional multilingual tiny model: https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin
SHA-256: be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21; size 77691713 bytes. Model is downloaded only on explicit selection, not shipped inside APK.
Caption runtime: yt-dlp 2026.8.19 (Unlicense), yt-dlp-ejs 0.8.0 (Unlicense), youtube-transcript-api 1.2.4 (MIT); official PyPI wheels. Distribution license metadata remains within site-packages.
FFmpeg and shared libraries are Alpine aarch64 packages; package licenses and source details at https://pkgs.alpinelinux.org/packages?name=ffmpeg (build includes GPL components). This media upgrade does not overwrite the user's package database.
Remote services are optional and not bundled or represented as connected until the user configures them. Listing a project is not proof of Android compatibility or a completed live service test.
