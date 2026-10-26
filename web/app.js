const video = document.querySelector("#match-video");
const matchButtons = [...document.querySelectorAll("[data-match]")];
const playerStatus = document.querySelector("#playback-status");
const videoError = document.querySelector("#video-error");
const matches = new Map();
let selectedId = "very-hard";

function announce(text) {
  playerStatus.textContent = text;
}

function playCurrent() {
  const attempt = video.play();
  if (attempt) attempt.catch(() => announce("录像已就绪，请使用播放器的播放按钮。"));
}

function selectMatch(id, play = true) {
  const match = matches.get(id);
  if (!match) {
    announce("录像列表尚未加载完成，请稍后重试。");
    return;
  }
  if (selectedId !== id) {
    video.pause();
    video.src = match.video;
    video.poster = match.poster;
    video.setAttribute("aria-label", match.title + "对局录像");
    video.load();
  }
  selectedId = id;
  videoError.hidden = true;
  document.querySelector("#match-category").textContent = match.category;
  document.querySelector("#match-title").textContent = match.title;
  document.querySelector("#match-description").textContent = match.description;
  document.querySelector("#video-link").href = match.video;
  document.querySelector("#video-error-link").href = match.video;
  for (const button of matchButtons) {
    const active = button.dataset.match === id;
    button.classList.toggle("active", active);
    button.setAttribute("aria-pressed", String(active));
  }
  announce("已选择：" + match.title);
  if (play) playCurrent();
}

const recordingsReady = fetch(new URL("./resource/matches.json", import.meta.url))
  .then(response => {
    if (!response.ok) throw new Error("Recording manifest unavailable");
    return response.json();
  })
  .then(recordings => {
    for (const match of recordings) {
      matches.set(match.id, match);
      const button = matchButtons.find(item => item.dataset.match === match.id);
      if (button) button.querySelector(".match-thumb > span").textContent = match.durationLabel;
    }
    selectMatch(selectedId, false);
  })
  .catch(() => announce("录像索引暂时无法加载，仍可使用播放器观看第一场对局。"));

for (const button of matchButtons) {
  button.addEventListener("click", async () => {
    await recordingsReady;
    selectMatch(button.dataset.match);
  });
}
for (const link of document.querySelectorAll("[data-play-match]")) {
  link.addEventListener("click", async () => {
    await recordingsReady;
    selectMatch(link.dataset.playMatch);
  });
}
video.addEventListener("error", () => {
  videoError.hidden = false;
  announce("录像加载失败，可以尝试在新窗口直接打开视频。");
});
video.addEventListener("playing", () => {
  videoError.hidden = true;
  announce("正在播放：" + document.querySelector("#match-title").textContent);
});

const menuToggle = document.querySelector(".menu-toggle");
const navigation = document.querySelector("#primary-nav");
function closeMenu() {
  menuToggle.setAttribute("aria-expanded", "false");
  navigation.classList.remove("open");
  menuToggle.setAttribute("aria-label", "展开导航");
}
menuToggle.addEventListener("click", () => {
  const open = menuToggle.getAttribute("aria-expanded") !== "true";
  menuToggle.setAttribute("aria-expanded", String(open));
  menuToggle.setAttribute("aria-label", open ? "收起导航" : "展开导航");
  navigation.classList.toggle("open", open);
});
navigation.addEventListener("click", event => {
  if (event.target.closest("a")) closeMenu();
});
document.addEventListener("keydown", event => {
  if (event.key === "Escape" && menuToggle.getAttribute("aria-expanded") === "true") {
    closeMenu();
    menuToggle.focus();
  }
});

const launchCode = document.querySelector("#launch-code");
const commands = {
  windows: "# 获取项目\ngit clone https://github.com/nine-T-nine/RWX-Agent.git\ncd RWX-Agent\n\n# 使用 Java 25 启动带 CLI 的游戏\n.\\gradlew.bat :desktop:rwxCliRun",
  linux: "# 获取项目\ngit clone https://github.com/nine-T-nine/RWX-Agent.git\ncd RWX-Agent\n\n# 使用 Java 25 启动 Headless 实例\nbash ./gradlew :desktop:run --args='--headless --no-steam'"
};
for (const button of document.querySelectorAll("[data-platform]")) {
  button.addEventListener("click", () => {
    launchCode.textContent = commands[button.dataset.platform];
    for (const other of document.querySelectorAll("[data-platform]")) {
      const active = other === button;
      other.classList.toggle("active", active);
      other.setAttribute("aria-pressed", String(active));
    }
  });
}

const toast = document.querySelector("#toast");
let toastTimer;
function showToast(text) {
  clearTimeout(toastTimer);
  toast.textContent = text;
  toast.hidden = false;
  toastTimer = setTimeout(() => { toast.hidden = true; }, 2600);
}
const copyButton = document.querySelector(".copy-button");
copyButton.addEventListener("click", async () => {
  try {
    if (!navigator.clipboard || !window.isSecureContext) throw new Error("Clipboard unavailable");
    await navigator.clipboard.writeText(launchCode.textContent);
    copyButton.querySelector("use").setAttribute("href", "#i-check");
    copyButton.querySelector("span").textContent = "已复制";
    showToast("启动命令已复制");
    setTimeout(() => {
      copyButton.querySelector("use").setAttribute("href", "#i-copy");
      copyButton.querySelector("span").textContent = "复制";
    }, 2200);
  } catch {
    const selection = window.getSelection();
    const range = document.createRange();
    range.selectNodeContents(launchCode);
    selection.removeAllRanges();
    selection.addRange(range);
    showToast("已选中命令，请按 Ctrl+C 或使用系统复制菜单");
  }
});
