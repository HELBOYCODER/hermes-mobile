---
name: video-shorts-editor
description: Inspect real video assets and render verified short-form edits when ffmpeg/ffprobe are available.
---

# Executable short-video editing

- Identify input media, ownership/permission, desired duration, target aspect ratio, subtitles, audio and export location.
- Check the actual sandbox first: `command -v ffmpeg && command -v ffprobe`. If unavailable, explain the missing dependency; do not pretend a video was rendered or install packages without approval.
- Inspect the source with `ffprobe -v error -show_entries format=duration:stream=codec_name,width,height,r_frame_rate -of json INPUT`; quote file paths safely and never concatenate untrusted strings into shell commands.
- Use ffmpeg for real transformations (trim, crop/pad, scale, subtitles when supported, audio normalization). Prefer a deterministic command and preserve the original.
- Render to a new output file. Verify nonzero size, decodable audio/video streams, duration and dimensions with ffprobe, and sample decode with `ffmpeg -v error -i OUTPUT -f null -`.
- If captions/transcripts require unavailable speech recognition, label them manual or pending; never fabricate timing.
- Return the exact output path, render command, probe result, duration, resolution, and any failed checks. A written editing plan is not a completed render.
