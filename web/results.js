/* Render the evaluation ledger from resource/results.json. */
const fmt = (v, digits = 1) => (typeof v === "number" ? v.toFixed(digits) : "—");
const fmtTotal = (v) => (typeof v === "number" ? v.toFixed(2) : "—");
const esc = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
const duration = (seconds) => {
  if (typeof seconds !== "number") return "—";
  const s = Math.round(seconds);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
};
const tier = (total) => (total >= 70 ? "high" : total >= 64 ? "mid" : "low");
const sessionShort = (id) => String(id || "").replace("session-", "").slice(0, 8);

function badge(result) {
  const cls = result.outcome === "defeat" ? "badge-defeat" : "badge-win";
  return `<span class="badge ${cls}">${esc(result.label)}</span>`;
}

function renderKpis(data) {
  const host = document.querySelector("#results-kpis");
  if (!host) return;
  const scores = data.current.map((m) => m.scores.total);
  const best = data.current.find((m) => m.id === data.overview.best.id);
  const items = [
    [String(data.overview.currentMatches), "当前标准对局", "total-v1"],
    [fmtTotal(Math.max(...scores)), "最高综合分", best ? best.title.split("·")[0].trim() : ""],
    [fmtTotal(scores.reduce((a, b) => a + b, 0) / scores.length), "当前标准均分",
      `${data.overview.currentMatches} 局`],
    [String(data.overview.legacyMatches), "历史归档对局", "仅作参考"],
  ];
  host.innerHTML = items
    .map(([value, label, hint]) => `<div class="kpi"><b>${esc(value)}</b><span>${esc(label)}</span><i>${esc(hint)}</i></div>`)
    .join("");
}

function renderCurrent(data) {
  const table = document.querySelector("#current-table");
  if (!table) return;
  const rows = data.current
    .slice()
    .sort((a, b) => b.scores.total - a.scores.total)
    .map((m) => `<tr>
      <td><span class="tier-dot tier-${tier(m.scores.total)}"></span><b>${fmtTotal(m.scores.total)}</b></td>
      <td><b>${esc(m.title)}</b><span class="cell-sub">${esc(m.tool)}</span></td>
      <td>${esc(m.agent.model)}<span class="cell-sub">推理强度 ${esc(m.agent.reasoning)} · ${esc(m.agent.provider)}</span></td>
      <td>${esc(m.date)}<span class="cell-sub">${esc(sessionShort(m.session))}</span></td>
      <td>${badge(m.result)}<span class="cell-sub">${esc(m.result.detail)}</span></td>
      <td>${fmt(m.scores.stage1)} / ${fmt(m.scores.stage2)}</td>
      <td><a class="row-link" href="#match-${esc(m.id)}" aria-label="查看 ${esc(m.title)} 的逐项扣分">明细</a></td>
    </tr>`)
    .join("");
  table.innerHTML = `<thead><tr><th scope="col">综合分</th><th scope="col">工具</th><th scope="col">模型</th><th scope="col">对局</th><th scope="col">结局</th><th scope="col">第一 / 第二阶段</th><th scope="col">明细</th></tr></thead><tbody>${rows}</tbody>`;
}

const FAMILY_LABEL = { AMBUSH: "偷袭", GRINDER: "绞肉机", STALE: "指令过期", BUILDER_IDLE: "建造者闲置", FACTORY_IDLE: "工厂闲置", FUNDS: "资金闲置" };

const capBadge = (points, cap) => (points >= cap - 1e-9 ? ' <span class="cap-hit">封顶</span>' : "");

function stage1Row(item) {
  return `<tr>
    <th scope="row">${esc(item.label)}</th>
    <td class="detail-points"><b>${fmt(item.points, 2)}</b>${capBadge(item.points, item.cap)}<span class="cell-sub">上限 ${fmt(item.cap, 0)}</span></td>
    <td class="detail-evidence">${esc(item.detail || "—")}</td>
  </tr>`;
}

function stage2Row(family) {
  const raw = family.raw > family.capped + 1e-9 ? `原始 ${fmt(family.raw, 1)} · ` : "";
  const events = family.events == null ? "" : ` · ${family.events} 段`;
  return `<tr>
    <th scope="row">${esc(FAMILY_LABEL[family.family] || family.family)}</th>
    <td class="detail-points"><b>${fmt(family.capped, 2)}</b>${capBadge(family.capped, family.cap)}<span class="cell-sub">${raw}上限 ${fmt(family.cap, 0)}${events}</span></td>
    <td class="detail-evidence">${esc(family.detail || "—")}</td>
  </tr>`;
}

const matchData = new Map();

function matchBody(m) {
  const notes = (m.notes || []).map((note) => `<li>${esc(note)}</li>`).join("");
  return `<div class="match-stages">
    <section class="match-stage">
      <h4><span>第一阶段</span>12 项 · 扣 ${fmt(m.breakdown.stage1.deduction, 2)} → ${fmt(m.breakdown.stage1.score, 2)}</h4>
      <div class="table-wrap detail-wrap">
        <table class="detail-table">
          <thead><tr><th scope="col">扣分项</th><th scope="col">扣分</th><th scope="col">本局证据</th></tr></thead>
          <tbody>${m.metrics.stage1Items.map(stage1Row).join("")}</tbody>
        </table>
      </div>
    </section>
    <section class="match-stage">
      <h4><span>第二阶段</span>6 类 · 扣 ${fmt(m.breakdown.stage2.deduction, 2)} → ${fmt(m.breakdown.stage2.score, 2)}</h4>
      <div class="table-wrap detail-wrap">
        <table class="detail-table">
          <thead><tr><th scope="col">扣分项</th><th scope="col">扣分</th><th scope="col">本局证据</th></tr></thead>
          <tbody>${m.breakdown.stage2.families.map(stage2Row).join("")}</tbody>
        </table>
      </div>
    </section>
  </div>
  ${notes ? `<ul class="match-notes">${notes}</ul>` : ""}`;
}

/** 默认全部折叠；第一次展开时才生成该局的两张表（按需加载）。 */
function renderMatchDetails(data) {
  const host = document.querySelector("#match-details");
  if (!host) return;
  const matches = data.current.slice().sort((a, b) => b.scores.total - a.scores.total);
  host.innerHTML = matches.map((m, index) => {
    matchData.set(m.id, m);
    const group = m.group === "trio" ? "同图对比组" : "单局记录";
    return `<details class="match-detail" id="match-${esc(m.id)}">
      <summary class="match-head">
        <span class="match-rank">${String(index + 1).padStart(2, "0")}</span>
        <span class="match-head-copy">
          <span class="match-title">${esc(m.title)}<span class="match-group">${group}</span></span>
          <span class="match-meta">${esc(m.date)} · ${esc(m.map)} · ${esc(m.opponent)} · ${esc(m.agent.model)}（推理强度 ${esc(m.agent.reasoning)}）· 会话 ${esc(sessionShort(m.session))}</span>
          <span class="match-tool">${esc(m.tool)}${m.toolDetail ? ` — ${esc(m.toolDetail)}` : ""}</span>
        </span>
        <span class="match-score">
          <b>${fmtTotal(m.scores.total)}</b>
          <span>综合分</span>
          <i>S1 ${fmt(m.scores.stage1)} · S2 ${fmt(m.scores.stage2)}</i>
        </span>
        <span class="match-toggle" aria-hidden="true"></span>
      </summary>
      <div class="match-body"></div>
    </details>`;
  }).join("");

  const open = (details) => {
    details.open = true;
    const body = details.querySelector(".match-body");
    if (!body || body.dataset.loaded === "1") return;
    const entry = matchData.get(details.id.replace(/^match-/, ""));
    if (!entry) return;
    body.innerHTML = matchBody(entry);
    body.dataset.loaded = "1";
  };

  for (const details of host.querySelectorAll("details.match-detail")) {
    details.addEventListener("toggle", () => {
      if (details.open) open(details);
    });
  }

  const openFromHash = () => {
    const hash = decodeURIComponent(window.location.hash.slice(1));
    const target = hash ? document.getElementById(hash) : null;
    if (!target || target.tagName !== "DETAILS") return;
    open(target);
    // 展开后才把表格插进来，页面高度会变；等一帧再滚，否则会被折叠时的高度截断。
    setTimeout(() => target.scrollIntoView({ block: "start" }), 80);
  };
  window.addEventListener("hashchange", openFromHash);
  host.addEventListener("click", (event) => {
    const link = event.target.closest('a[href^="#match-"]');
    if (!link) return;
    const target = document.querySelector(link.getAttribute("href"));
    if (target && target.tagName === "DETAILS") open(target);
  });
  openFromHash();
}

function renderLegacy(data) {  const table = document.querySelector("#legacy-table");
  if (!table) return;
  table.innerHTML = `<thead><tr><th scope="col">对局日期</th><th scope="col">模型</th><th scope="col">工具</th><th scope="col">第一阶段</th><th scope="col">第二阶段</th><th scope="col">备注</th></tr></thead>
    <tbody>${data.legacy
      .map((m) => `<tr><td>${esc(m.date)}</td><td>${esc(m.model)}</td><td>${esc(m.tool)}</td><td>${fmt(m.scores.stage1)}</td><td>${fmt(m.scores.stage2)}</td><td><span class="cell-sub">${esc(m.note)}</span></td></tr>`)
      .join("")}</tbody>`;
}

function renderGaps(data) {
  const host = document.querySelector("#gap-list");
  if (!host) return;
  host.innerHTML = data.insufficient
    .map((g) => `<article class="gap"><h3>${esc(g.title)}</h3><p class="gap-date">${esc(g.date)}</p><p>${esc(g.detail)}</p><p class="gap-impact">${esc(g.impact)}</p></article>`)
    .join("");
}

function renderMethod(data) {
  const sources = document.querySelector("#method-sources");
  const limits = document.querySelector("#method-limits");
  if (sources) sources.innerHTML = data.method.sources.map((s) => `<li>${esc(s)}</li>`).join("");
  if (limits) limits.innerHTML = data.method.limits.map((s) => `<li>${esc(s)}</li>`).join("");
}

function scoringTable(items, caption) {
  return `<div class="table-wrap scoring-table-wrap"><table class="data-table scoring-table">
    <caption>${esc(caption)}</caption>
    <thead><tr><th scope="col">扣分项</th><th scope="col">上限</th><th scope="col">评分公式 / 参数</th><th scope="col">说明</th></tr></thead>
    <tbody>${items.map((item) => `<tr>
      <th scope="row">${esc(item.name)}</th>
      <td><b>${esc(item.cap)}</b></td>
      <td class="formula-cell">${esc(item.formula)}</td>
      <td class="description-cell">${esc(item.description)}</td>
    </tr>`).join("")}</tbody>
  </table></div>`;
}

function renderScoring(data) {
  const stages = document.querySelector("#full-match-scoring");
  const cases = document.querySelector("#micro-cases-list");
  if (stages) {
    stages.innerHTML = data.fullMatch.stages.map((stage) => `<article class="scoring-stage">
      <header class="scoring-stage-head">
        <div><span>${esc(stage.label)} / ${esc(stage.version)}</span><h3>${esc(stage.title)}</h3></div>
        <p>${esc(stage.summary)}</p>
      </header>
      ${scoringTable(stage.items, `${stage.label}扣分项，共 ${stage.items.length} 项`)}
    </article>`).join("");
  }
  if (cases) {
    cases.innerHTML = data.microCases.map((item) => `<article class="micro-case" id="${esc(item.id)}">
      <div class="case-overview">
        <figure class="case-figure">
          <img src="${esc(item.image)}" alt="${esc(item.imageAlt)}" width="1536" height="864" loading="lazy">
          <figcaption>${esc(item.imageCaption)}</figcaption>
        </figure>
        <div class="case-copy">
          <span class="case-label">${esc(item.label)} / ${esc(item.version)}</span>
          <h3>${esc(item.title)}</h3>
          <p>${esc(item.environment)}</p>
          <div class="case-score-rule"><b>CASE 得分</b><span>max(0, 100 − Σ 每项封顶扣分)</span></div>
        </div>
      </div>
      ${scoringTable(item.items, `${item.label} 扣分项，共 ${item.items.length} 项`)}
    </article>`).join("");
  }
  const anchor = decodeURIComponent(window.location.hash.slice(1));
  const scoringAnchors = new Set(["scoring", "micro-cases", ...data.microCases.map((item) => item.id)]);
  if (scoringAnchors.has(anchor)) {
    requestAnimationFrame(() => document.getElementById(anchor)?.scrollIntoView());
  }
}

const standardLine = document.querySelector("#standard-line");

const menuToggle = document.querySelector(".menu-toggle");
const navigation = document.querySelector("#primary-nav");
if (menuToggle && navigation) {
  const closeMenu = () => {
    menuToggle.setAttribute("aria-expanded", "false");
    menuToggle.setAttribute("aria-label", "展开导航");
    navigation.classList.remove("open");
  };
  menuToggle.addEventListener("click", () => {
    const open = menuToggle.getAttribute("aria-expanded") !== "true";
    menuToggle.setAttribute("aria-expanded", String(open));
    menuToggle.setAttribute("aria-label", open ? "收起导航" : "展开导航");
    navigation.classList.toggle("open", open);
  });
  navigation.addEventListener("click", (event) => {
    if (event.target.closest("a")) closeMenu();
  });
}

fetch(new URL("./resource/results.json", import.meta.url))
  .then((response) => {
    if (!response.ok) throw new Error("results.json unavailable");
    return response.json();
  })
  .then((data) => {
    renderKpis(data);
    renderCurrent(data);
    renderMatchDetails(data);
    renderLegacy(data);
    renderGaps(data);
    renderMethod(data);
    if (standardLine) {
      const params = data.standard.parameters?.ambush;
      const ambush = params ? `；第二阶段偷袭上限 ${params.cap}、单次上限 ${params.singleMax}` : "";
      standardLine.textContent = `${data.standard.total} = ${data.standard.stage1} × ${data.standard.weights.stage1} + ${data.standard.stage2} × ${data.standard.weights.stage2}${ambush}；更新于 ${data.generatedAt.slice(0, 10)}。`;
    }
    document.documentElement.classList.add("results-ready");
  })
  .catch(() => {
    document.querySelectorAll(".data-pending").forEach((el) => {
      el.textContent = "数据未加载：请通过 HTTP 服务打开页面（见 web/README.md）。";
      el.hidden = false;
    });
  });

fetch(new URL("./resource/scoring.json", import.meta.url))
  .then((response) => {
    if (!response.ok) throw new Error("scoring.json unavailable");
    return response.json();
  })
  .then((data) => renderScoring(data))
  .catch(() => {
    const status = document.querySelector("#scoring-status");
    if (status) {
      status.textContent = "评分方法未加载：请通过 HTTP 服务打开页面，或直接查看 resource/scoring.json。";
      status.hidden = false;
    }
  });
