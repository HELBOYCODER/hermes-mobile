#!/usr/bin/env python3
"""Bounded public-link evidence extraction. Outputs evidence, never invented transcripts."""
import argparse
import json
import pathlib
import re
import sys
import urllib.parse

MAX_DURATION = 1200
MAX_BYTES = 100 * 1024 * 1024

class MediaError(Exception):
    pass

def normalize_url(url):
    u = urllib.parse.urlsplit(url)
    if u.scheme != 'https' or u.username or u.password or u.port:
        raise MediaError('Use a public HTTPS YouTube or Instagram link')
    host = (u.hostname or '').lower()
    if host in ('youtu.be', 'youtube.com', 'www.youtube.com', 'm.youtube.com'):
        video = u.path.strip('/') if host == 'youtu.be' else (
            u.path.split('/')[2] if u.path.startswith(('/shorts/', '/embed/', '/live/')) else
            urllib.parse.parse_qs(u.query).get('v', [''])[0])
        if not re.fullmatch(r'[A-Za-z0-9_-]{11}', video):
            raise MediaError('Invalid YouTube video link')
        return 'https://www.youtube.com/watch?v=' + video
    if host in ('instagram.com', 'www.instagram.com'):
        parts = u.path.strip('/').split('/')
        if len(parts) != 2 or parts[0] not in ('p', 'reel', 'tv') or not re.fullmatch(r'[A-Za-z0-9_-]{1,100}', parts[1]):
            raise MediaError('Use an Instagram post or reel link')
        return 'https://www.instagram.com/' + '/'.join(parts) + '/'
    raise MediaError('Unsupported media host')

def download_guard(limit=MAX_BYTES):
    def check(event):
        if max(event.get('downloaded_bytes') or 0, event.get('total_bytes') or 0) > limit:
            raise MediaError('Download exceeds the configured byte budget')
    return check

def _info(url):
    import yt_dlp
    with yt_dlp.YoutubeDL({'quiet': True, 'no_warnings': True, 'noplaylist': True,
                          'skip_download': True, 'socket_timeout': 15, 'retries': 1,
                          'js_runtimes': {'node': {}}}) as y:
        return y.extract_info(url, download=False)

def _captions(url):
    if 'youtube.com/watch?' not in url:
        return []
    from youtube_transcript_api import YouTubeTranscriptApi
    video = urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)['v'][0]
    api = YouTubeTranscriptApi()
    try:
        transcript = api.fetch(video, languages=['fa', 'en'])
    except Exception:
        available = api.list(video)
        track = next(iter(available))
        transcript = track.fetch()
    return [{'start': s.start, 'duration': s.duration, 'text': s.text,
             'language': getattr(transcript, 'language_code', '')} for s in transcript]

def extract(url, directory, caption_fetcher=None, info_fetcher=None):
    url = normalize_url(url)
    directory = pathlib.Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    try:
        segments = (caption_fetcher or _captions)(url)
    except Exception:
        segments = []
    try:
        info = (info_fetcher or _info)(url) or {}
    except Exception:
        if not segments:
            raise MediaError('Content unavailable, restricted, or requires its owner-authorized session')
        info = {}
    if float(info.get('duration') or 0) > MAX_DURATION:
        raise MediaError('Media exceeds the 20 minute limit')
    clean = []
    chars = 0
    for segment in segments:
        start = max(0, float(segment.get('start') or 0))
        if start >= MAX_DURATION:
            raise MediaError('Transcript exceeds the 20 minute limit')
        text = str(segment.get('text') or '')
        chars += len(text)
        if chars > 200_000:
            raise MediaError('Transcript exceeds text budget')
        clean.append({'start': start, 'end': min(MAX_DURATION, start + max(0, float(segment.get('duration') or 0))), 'text': text})
    evidence = {'source_url': url, 'title': str(info.get('title') or '')[:1000],
                'description': str(info.get('description') or '')[:12000],
                'duration_seconds': info.get('duration'),
                'language': segments[0].get('language', '') if segments else '',
                'coverage': 'captions' if clean else 'metadata',
                'method': 'youtube-transcript-api' if clean else 'yt-dlp metadata',
                'segments': clean, 'artifacts': [],
                'limitation': '' if clean else 'No transcript obtained. This is metadata, not a full video analysis.'}
    path = directory / 'evidence.json'
    path.write_text(json.dumps(evidence, ensure_ascii=False), encoding='utf-8')
    evidence['artifacts'].append(str(path))
    if clean:
        transcript_path = directory / 'transcript.txt'
        transcript_path.write_text('\n'.join(f"[{s['start']:.1f}s] {s['text']}" for s in clean), encoding='utf-8')
        evidence['artifacts'].append(str(transcript_path))
    return evidence

def download_audio(url, directory):
    import yt_dlp
    url = normalize_url(url)
    info = _info(url)
    duration = info.get('duration') or 0
    if not 0 < duration <= MAX_DURATION:
        raise MediaError('Audio duration must be known and at most 20 minutes')
    target = pathlib.Path(directory)
    try:
        with yt_dlp.YoutubeDL({'quiet': True, 'no_warnings': True, 'noplaylist': True,
                              'format': 'bestaudio/best', 'outtmpl': str(target / 'audio.%(ext)s'),
                              'max_filesize': MAX_BYTES, 'socket_timeout': 15, 'retries': 1,
                              'js_runtimes': {'node': {}}, 'progress_hooks': [download_guard()],
                              'postprocessors': [{'key': 'FFmpegExtractAudio', 'preferredcodec': 'wav'}],
                              'postprocessor_args': ['-ac', '1', '-ar', '16000']}) as y:
            y.download([url])
        audio = target / 'audio.wav'
        if not audio.is_file() or audio.stat().st_size > MAX_BYTES:
            raise MediaError('Audio was not available within the configured budget')
        return str(audio)
    except Exception:
        for f in target.glob('audio.*'):
            f.unlink(missing_ok=True)
        raise MediaError('Audio download unavailable; upload a file or use available captions')

def main():
    p = argparse.ArgumentParser()
    p.add_argument('url'); p.add_argument('--output', required=True); p.add_argument('--audio', action='store_true')
    a = p.parse_args()
    try:
        r = extract(a.url, a.output)
        if a.audio and not r['segments']:
            r['audio_path'] = download_audio(a.url, a.output)
        print(json.dumps(r, ensure_ascii=False))
    except Exception as e:
        print(json.dumps({'error': str(e) if isinstance(e, MediaError) else 'Media dependencies unavailable', 'coverage': 'none'}))
        sys.exit(1)
if __name__ == '__main__':
    main()
