"""Validate deployable site paths, recording integrity and GitHub size limits."""

import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
from urllib.parse import unquote, urlsplit

WEB = Path(__file__).resolve().parents[1]


class Page(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids = []
        self.links = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if 'id' in attrs:
            self.ids.append(attrs['id'])
        for name in ('src', 'href', 'poster'):
            if attrs.get(name):
                self.links.append(attrs[name])


if __name__ == '__main__':
    page = Page()
    html = (WEB / 'index.html').read_text(encoding='utf-8')
    page.feed(html)
    assert len(page.ids) == len(set(page.ids)), 'Duplicate page IDs'
    for link in page.links:
        parsed = urlsplit(link)
        if parsed.scheme or parsed.netloc:
            continue
        if parsed.path:
            target = (WEB / unquote(parsed.path)).resolve()
            assert target.is_relative_to(WEB), 'A site asset escapes web/: ' + link
            assert target.is_file(), 'Missing site asset: ' + link
        elif parsed.fragment:
            assert parsed.fragment in page.ids, 'Missing page anchor: ' + link
    assert '<title>RWX-Agent：让智能体参与经典RTS游戏Rusted Warfare</title>' in html
    assert 'https://github.com/nine-T-nine/RWX-Agent' in html
    assert 'https://github.com/yomi2539/RWX' in html
    assert '并定期同步上游源码' in html
    recordings = json.loads((WEB / 'resource/matches.json').read_text(encoding='utf-8'))
    assert len(recordings) == 4
    assert len({item['id'] for item in recordings}) == 4
    for item in recordings:
        video = WEB / item['video']
        assert video.stat().st_size == item['bytes'], 'Video size mismatch: ' + item['id']
        assert video.stat().st_size < 100 * 1024 * 1024, 'Video exceeds GitHub limit'
        assert hashlib.sha256(video.read_bytes()).hexdigest() == item['sha256'], 'Video checksum mismatch: ' + item['id']
        assert (WEB / item['poster']).is_file()
        assert item['video'] in html, 'Missing no-JavaScript video link: ' + item['id']
    deployed = [WEB / name for name in ('index.html', 'styles.css', 'app.js', 'favicon.svg', '.nojekyll', 'resource/matches.json')]
    for folder in ('matches', 'posters'):
        deployed.extend(p for p in (WEB / 'resource' / folder).rglob('*') if p.is_file())
    total = sum(path.stat().st_size for path in deployed)
    assert total < 1_000_000_000, 'Site exceeds GitHub Pages size limit'
    print(f'PASS: {len(recordings)} recordings, {len(deployed)} deployable files, {total / 1024 / 1024:.1f} MiB; local links and video checksums valid.')
