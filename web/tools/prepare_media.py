"""Create browser-ready copies of the supplied recordings. Requires FFmpeg."""

from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import subprocess

WEB = Path(__file__).resolve().parents[1]
RESOURCE = WEB / 'resource'
RECORDINGS = [
    {'id': 'very-hard', 'source': 'gpt6.1solvsVeryHard.mp4', 'title': '与 Very Hard AI 正面交锋',
     'category': 'AGENT VS BUILT-IN AI', 'tag': '经典对抗',
     'description': '从基地经营到部队推进，观察智能体如何在实时战局中分配资源、组织生产与执行作战指令。'},
    {'id': 'agent-vs-agent', 'source': 'gpt6.1solvsClaudeOpus5.5-水田.mp4', 'title': '两位智能体，一张水田地图',
     'category': 'AGENT VS AGENT', 'tag': '智能体对战',
     'description': 'GPT-6.1 Sol 与 Claude Opus 5.5 的水田对局。不同决策方式，在同一套游戏规则下展开交锋。'},
    {'id': 'claude-pause', 'source': 'claudevsveryHard-利用暂停.mp4', 'title': 'Claude 利用暂停对战 Very Hard AI',
     'category': 'AGENT VS BUILT-IN AI', 'tag': '暂停战术',
     'description': 'Claude 对战 Very Hard AI，利用暂停观察战局并安排下一步行动。'},
]


def run(args):
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)


def prepare(item):
    source = RESOURCE / item['source']
    if not source.is_file():
        raise FileNotFoundError(source)
    video = RESOURCE / 'matches' / (item['id'] + '.mp4')
    poster = RESOURCE / 'posters' / (item['id'] + '.webp')
    video.parent.mkdir(parents=True, exist_ok=True)
    poster.parent.mkdir(parents=True, exist_ok=True)
    probe = json.loads(subprocess.check_output([
        'ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'json', str(source)]))
    duration = float(probe['format']['duration'])
    if not video.exists():
        run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-nostdin', '-y', '-i', str(source),
             '-map', '0:v:0', '-map', '0:a:0?', '-c:v', 'libx264', '-preset', 'veryfast',
             '-crf', '24', '-pix_fmt', 'yuv420p', '-threads', '3', '-c:a', 'aac', '-b:a', '96k',
             '-map_metadata', '-1', '-movflags', '+faststart', str(video)])
    if video.stat().st_size >= 95 * 1024 * 1024:
        raise ValueError(f'Web recording is too large for GitHub: {video.name}')
    run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-nostdin', '-y', '-ss', str(duration * .35),
         '-i', str(source), '-frames:v', '1', '-vf', 'scale=1280:-2', '-c:v', 'libwebp', '-quality', '86', str(poster)])
    seconds = round(duration)
    result = dict(item, video='resource/matches/' + video.name, poster='resource/posters/' + poster.name,
                  duration=duration, durationLabel=f'{seconds // 60}:{seconds % 60:02}',
                  bytes=video.stat().st_size, sha256=hashlib.sha256(video.read_bytes()).hexdigest())
    print(json.dumps({'id': item['id'], 'bytes': result['bytes'], 'duration': duration}), flush=True)
    return result


if __name__ == '__main__':
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(prepare, RECORDINGS))
    (RESOURCE / 'matches.json').write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-nostdin', '-y', '-ss', '80',
         '-i', str(RESOURCE / RECORDINGS[0]['source']), '-frames:v', '1',
         '-vf', 'crop=1600:900:0:0,scale=1600:-2', '-c:v', 'libwebp', '-quality', '89',
         str(RESOURCE / 'posters/hero.webp')])
    print(f'Prepared all {len(results)} recordings, posters and resource/matches.json.', flush=True)
