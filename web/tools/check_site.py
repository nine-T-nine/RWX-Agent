"""Validate deployable site paths, recording integrity, ledger data and GitHub size limits."""

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


def parse(name):
    page = Page()
    page.feed((WEB / name).read_text(encoding='utf-8'))
    assert len(page.ids) == len(set(page.ids)), 'Duplicate page IDs in ' + name
    for link in page.links:
        parsed = urlsplit(link)
        if parsed.scheme or parsed.netloc:
            continue
        if parsed.path:
            target = (WEB / unquote(parsed.path)).resolve()
            assert target.is_relative_to(WEB), 'A site asset escapes web/: ' + link
            assert target.is_file(), 'Missing site asset: ' + link
        elif parsed.fragment:
            assert parsed.fragment in page.ids, 'Missing page anchor in ' + name + ': ' + link
    return page


def check_ledger():
    data = json.loads((WEB / 'resource/results.json').read_text(encoding='utf-8'))
    assert data['schema'] == 1
    standard = data['standard']
    assert standard['total'] and standard['stage1'] and standard['stage2']
    weights = standard['weights']
    current = data['current']
    assert len(current) == data['overview']['currentMatches'] >= 3
    assert len({item['id'] for item in current}) == len(current)
    ids = {item['id'] for item in current}
    assert data['overview']['best']['id'] in ids
    for item in current:
        for key in ('title', 'tool', 'date', 'result', 'scores', 'metrics', 'runtime', 'session'):
            assert key in item, f'{item["id"]}: missing {key}'
        assert item['agent']['model'], item['id'] + ': model is required'
        assert item['agent']['reasoning'], item['id'] + ': reasoning effort is required'
        assert item['tool'], item['id'] + ': tool is required'
        scores = item['scores']
        expected = scores['stage1'] * weights['stage1'] + scores['stage2'] * weights['stage2']
        assert abs(scores['total'] - expected) < 0.05, item['id'] + ': total does not match weighted stages'
        assert 0 <= scores['total'] <= 100
    legacy = data['legacy']
    assert len(legacy) == data['overview']['legacyMatches'] >= 1
    for item in legacy:
        assert item['model'] and item['tool'] and item['note']
        assert 0 <= item['scores']['stage1'] <= 100
        assert 0 <= item['scores']['stage2'] <= 100
    assert data['insufficient'], 'material gaps must be recorded'
    assert data['method']['limits'], 'known limits must be recorded'
    return data


def check_scoring():
    data = json.loads((WEB / 'resource/scoring.json').read_text(encoding='utf-8'))
    assert data['schema'] == 1
    stages = data['fullMatch']['stages']
    assert [stage['id'] for stage in stages] == ['stage1', 'stage2']
    assert len(stages[0]['items']) == 12, 'Stage 1 must list all 12 deduction items'
    assert len(stages[1]['items']) == 6, 'Stage 2 must list all 6 deduction families'
    cases = data['microCases']
    assert [case['id'] for case in cases] == ['case1', 'case2', 'case3', 'case4']
    for group in [*stages, *cases]:
        assert group['version'] and group['items']
        for item in group['items']:
            assert item['name'] and item['formula'] and item['description']
            assert item['cap'] > 0
    for case in cases:
        assert case['environment'] and case['imageAlt'] and case['imageCaption']
        image = (WEB / case['image'].removeprefix('./')).resolve()
        assert image.is_relative_to(WEB) and image.is_file(), 'Missing CASE image: ' + case['image']
    return data


if __name__ == '__main__':
    page = parse('index.html')
    html = (WEB / 'index.html').read_text(encoding='utf-8')
    assert '<title>RWX-Agent：让智能体参与经典RTS游戏Rusted Warfare</title>' in html
    assert 'https://github.com/nine-T-nine/RWX-Agent' in html
    assert 'https://github.com/yomi2539/RWX' in html
    assert '并定期同步上游源码' in html

    ledger_page = parse('results.html')
    ledger_html = (WEB / 'results.html').read_text(encoding='utf-8')
    assert ledger_page.ids, 'Ledger page must expose anchor targets'
    assert '<title>评测结果｜RWX-Agent</title>' in ledger_html
    assert 'resource/results.json' in (WEB / 'results.js').read_text(encoding='utf-8')
    assert (WEB / 'results.js').is_file()
    assert './results.html' in html, 'Home page must link to the ledger'

    recordings = json.loads((WEB / 'resource/matches.json').read_text(encoding='utf-8'))
    assert len(recordings) == 3
    assert len({item['id'] for item in recordings}) == 3
    for item in recordings:
        video = WEB / item['video']
        assert video.stat().st_size == item['bytes'], 'Video size mismatch: ' + item['id']
        assert video.stat().st_size < 100 * 1024 * 1024, 'Video exceeds GitHub limit'
        assert hashlib.sha256(video.read_bytes()).hexdigest() == item['sha256'], 'Video checksum mismatch: ' + item['id']
        assert (WEB / item['poster']).is_file()
        assert item['video'] in html, 'Missing no-JavaScript video link: ' + item['id']

    ledger = check_ledger()
    scoring = check_scoring()

    deployed = [WEB / name for name in ('index.html', 'styles.css', 'app.js', 'results.html', 'results.js', 'favicon.svg', '.nojekyll',
                                        'resource/matches.json', 'resource/results.json', 'resource/scoring.json')]
    for folder in ('matches', 'posters', 'cases'):
        deployed.extend(p for p in (WEB / 'resource' / folder).rglob('*') if p.is_file())
    total = sum(path.stat().st_size for path in deployed)
    assert total < 1_000_000_000, 'Site exceeds GitHub Pages size limit'
    print(f'PASS: {len(recordings)} recordings, {ledger["overview"]["currentMatches"]} current matches, '
          f'{ledger["overview"]["legacyMatches"]} archived, {len(scoring["microCases"])} micro cases, '
          f'{len(deployed)} deployable files, '
          f'{total / 1024 / 1024:.1f} MiB; local links, video checksums and ledger scores valid.')
