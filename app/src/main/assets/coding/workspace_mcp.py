#!/usr/bin/env python3
"""Bundled read-only coding MCP. stdlib only, newline-delimited JSON-RPC."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import selectors
import tempfile
import sys
import time

SKIP = {'.git', '.hermes', 'node_modules', '.gradle', 'build', 'dist', '.venv', 'venv', '__pycache__', '.idea'}
MANIFESTS = {'package.json', 'pyproject.toml', 'Cargo.toml', 'go.mod', 'build.gradle', 'build.gradle.kts', 'settings.gradle', 'Makefile'}

def resolve(root, raw='.'):
    path = (root / raw).resolve()
    if not path.is_relative_to(root):
        raise ValueError('Path must stay inside the configured projects directory')
    if not path.is_dir():
        raise ValueError('Project directory does not exist')
    return path

def files(project):
    count, directories, start = 0, 0, time.monotonic()
    for base, dirs, names in os.walk(project, followlinks=False):
        directories += 1
        if directories > 5000 or time.monotonic() - start > 5:
            return
        dirs[:] = sorted(d for d in dirs if d not in SKIP and not d.startswith('.') and not (Path(base) / d).is_symlink())
        for name in sorted(names):
            path = Path(base) / name
            if name.startswith('.') or path.is_symlink() or not path.resolve().is_relative_to(project):
                continue
            yield path
            count += 1
            if count >= 5000:
                return

def git(project, args):
    command = ['git', '-c', 'core.fsmonitor=false', '-c', 'core.hooksPath=/dev/null', '-C', str(project)] + args
    environment = {**os.environ, 'GIT_OPTIONAL_LOCKS':'0', 'GIT_PAGER':'cat', 'GIT_EXTERNAL_DIFF':''}
    with tempfile.TemporaryFile() as errors:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=errors, env=environment)
        selector = selectors.DefaultSelector()
        selector.register(process.stdout, selectors.EVENT_READ)
        data, deadline, clipped = bytearray(), time.monotonic() + 8, False
        try:
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0 or not selector.select(remaining):
                    raise ValueError('Git command timed out')
                chunk = os.read(process.stdout.fileno(), min(4096, 24001 - len(data)))
                if not chunk:
                    break
                data.extend(chunk)
                if len(data) > 24000:
                    clipped = True
                    process.kill()
                    break
            code = process.wait(timeout=max(0.1, deadline - time.monotonic()))
            if code and not clipped:
                errors.seek(0)
                raise ValueError(errors.read(500).decode(errors='replace') or 'Git command failed')
            return data[:24000].decode(errors='replace') + ('\n[output truncated]' if clipped else '')
        finally:
            selector.close()
            process.stdout.close()
            if process.poll() is None:
                process.kill()
            process.wait()

def call(root, name, args):
    project = resolve(root, str(args.get('project', '.')))
    if name == 'project_overview':
        paths = [str(p.relative_to(project)) for p in files(project)]
        return {'project': str(project), 'files': paths[:400], 'manifests': [p for p in paths if Path(p).name in MANIFESTS][:40], 'truncated': len(paths) > 400}
    if name == 'search_code':
        needle = str(args.get('query', ''))
        if not needle or len(needle) > 500:
            raise ValueError('query must contain 1–500 characters')
        hits, scanned, start = [], 0, time.monotonic()
        for path in files(project):
            if time.monotonic() - start > 5:
                return {'matches': hits, 'truncated': True, 'scanned_files': scanned}
            try:
                if path.stat().st_size > 1_000_000:
                    continue
                data = path.read_bytes()
                if b'\0' in data[:8000]:
                    continue
                scanned += 1
                for line, text in enumerate(data.decode('utf-8', errors='replace').splitlines(), 1):
                    if needle.casefold() in text.casefold():
                        hits.append({'file': str(path.relative_to(project)), 'line': line, 'text': text[:400]})
                        if len(hits) >= 60:
                            return {'matches': hits, 'truncated': True, 'scanned_files': scanned}
            except OSError:
                continue
        return {'matches': hits, 'truncated': False, 'scanned_files': scanned}
    if name == 'git_changes':
        top = Path(git(project, ['rev-parse', '--show-toplevel']).strip()).resolve()
        if not top.is_relative_to(root):
            raise ValueError('Repository root must stay inside projects directory')
        return {'status': git(project, ['status', '--porcelain=v1']), 'diff': git(project, ['diff', '--no-ext-diff', '--no-textconv', '--unified=3', '--', '.']), 'staged_diff': git(project, ['diff', '--cached', '--no-ext-diff', '--no-textconv', '--unified=3', '--', '.'])}
    raise ValueError('Unknown tool')

def tools():
    out = []
    for name, description in [('project_overview', 'List project files and detect build manifests without changing files.'), ('search_code', 'Search source text using a bounded literal, case-insensitive query.'), ('git_changes', 'Read Git status and tracked changes without executing hooks or modifying files.')]:
        properties = {'project': {'type':'string', 'description':'Path relative to /root/projects, or an absolute path within it; defaults to .'}}
        if name == 'search_code':
            properties['query'] = {'type':'string', 'description':'Literal source text to find'}
        out.append({'name':name, 'description':description, 'inputSchema':{'type':'object', 'properties':properties, 'required':['query'] if name == 'search_code' else [], 'additionalProperties':False}, 'annotations':{'readOnlyHint':True, 'destructiveHint':False, 'openWorldHint':False}})
    return out

def serve(root):
    for line in sys.stdin:
        request = None
        try:
            request = json.loads(line)
            if not isinstance(request, dict):
                raise ValueError('Request must be an object')
            if 'id' not in request:
                continue
            method = request.get('method')
            if method == 'initialize':
                result = {'protocolVersion':'2025-06-18', 'capabilities':{'tools':{}}, 'serverInfo':{'name':'Hermes Coding Workspace', 'version':'1.0.0'}}
            elif method == 'ping':
                result = {}
            elif method == 'tools/list':
                result = {'tools':tools()}
            elif method == 'tools/call':
                params = request.get('params', {})
                try:
                    data = call(root, params.get('name'), params.get('arguments', {}))
                    result = {'content':[{'type':'text', 'text':json.dumps(data, ensure_ascii=False)}], 'isError':False}
                except (ValueError, OSError, subprocess.SubprocessError) as error:
                    result = {'content':[{'type':'text', 'text':str(error)[:500]}], 'isError':True}
            else:
                print(json.dumps({'jsonrpc':'2.0', 'id':request['id'], 'error':{'code':-32601, 'message':'Method not found'}}), flush=True)
                continue
            reply = {'jsonrpc':'2.0', 'id':request['id'], 'result':result}
        except (ValueError, TypeError, AttributeError) as error:
            reply = {'jsonrpc':'2.0', 'id':request.get('id') if isinstance(request, dict) else None, 'error':{'code':-32602, 'message':str(error)[:300]}}
        print(json.dumps(reply, ensure_ascii=False), flush=True)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', default='/root/projects')
    root = Path(parser.parse_args().root).resolve()
    root.mkdir(parents=True, exist_ok=True)
    serve(root)
