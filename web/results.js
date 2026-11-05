/* Render the evaluation ledger from resource/results.json. */
const fmt = (v, digits = 1) => (typeof v === "number" ? v.toFixed(digits) : "—");
const fmtTotal = (v) => (typeof v === "number" ? v.toFixed(2) : "—");
const scorePair = (v, cap = 100, digits = 1) => (typeof v === "number" ? `${v.toFixed(digits)}/${cap}` : "—");
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
    [scorePair(Math.max(...scores), 100, 2), "最高综合分", best ? best.title.split("·")[0].trim() : ""],
    [scorePair(scores.reduce((a, b) => a + b, 0) / scores.length, 100, 2), "当前标准均分",
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
      <td><span class="tier-dot tier-${tier(m.scores.total)}"></span><b>${scorePair(m.scores.total, 100, 2)}</b></td>
      <td><b>${esc(m.title)}</b><span class="cell-sub">${esc(m.tool)}</span></td>
      <td>${esc(m.agent.model)}<span class="cell-sub">推理强度 ${esc(m.agent.reasoning)} · ${esc(m.agent.provider)}</span></td>
      <td><b>${esc(m.configuration?.map || m.map)}</b><span class="cell-sub">${esc(m.configuration?.ai || m.opponent)}</span><span class="cell-sub">有效 ${duration(m.configuration?.durationSeconds ?? m.metrics?.durationSeconds)} · 游戏 ${duration(m.configuration?.gameSeconds)}</span></td>
      <td>${esc(m.date)}<span class="cell-sub">${esc(sessionShort(m.session))}</span></td>
      <td>${badge(m.result)}<span class="cell-sub">${esc(m.result.detail)}</span></td>
      <td>${scorePair(m.scores.stage1)} / ${scorePair(m.scores.stage2)}</td>
      <td><a class="row-link" href="#match-${esc(m.id)}" aria-label="查看 ${esc(m.title)} 的逐项扣分">明细</a></td>
    </tr>`)
    .join("");
  table.innerHTML = `<thead><tr><th scope="col">综合分</th><th scope="col">对局显示名 / 工具</th><th scope="col">模型</th><th scope="col">地图配置</th><th scope="col">对局</th><th scope="col">结局</th><th scope="col">第一 / 第二阶段</th><th scope="col">明细</th></tr></thead><tbody>${rows}</tbody>`;
}

const FAMILY_LABEL = { AMBUSH: "偷袭", GRINDER: "绞肉机", STALE: "指令过期", BUILDER_IDLE: "建造者闲置", FACTORY_IDLE: "工厂闲置", FUNDS: "资金闲置" };

const STAGE1_SHORT = {
  "A1-first-order": "首个行动",
  "A1-first-mining": "首个采矿",
  "A1-first-builder": "建造者",
  "A2-decision-cycle": "A→B 周期",
  "A2-wait-after-action": "B→A 等待",
  "A2-wait-outliers": "等待离群",
  "A2-decision-outliers": "决策离群",
  "A4-native-cadence": "底层间隔",
  "A5-AA": "A→A 离群",
  "A5-BB": "B→B 离群",
  "A4-production-rate": "生产/分",
  "A5-query-action-ratio": "A/B",
};

const STAGE2_SHORT = {
  AMBUSH: "偷袭",
  GRINDER: "绞肉机",
  STALE: "指令过期",
  BUILDER_IDLE: "建造闲置",
  FACTORY_IDLE: "工厂闲置",
  FUNDS: "资金闲置",
};

function matrixCell(points, cap, tip) {
  const hit = points > 0 && points >= cap - 1e-9;
  const text = `${fmt(points, 2)}/${cap}`;
  return `<td class="matrix-cell${points > 0 ? " is-hit" : ""}${hit ? " is-capped" : ""}" title="${esc(tip)}">${text}</td>`;
}

/** 一张矩阵表：行是对局、列是 18 个扣分项，格子里只有扣分，证据挂在 title 上。 */
function renderMatrix(data) {
  const host = document.querySelector("#match-matrix");
  if (!host) return;
  const matches = data.current.slice().sort((a, b) => b.scores.total - a.scores.total);
  const stage1 = matches[0].metrics.stage1Items;
  const stage2 = matches[0].breakdown.stage2.families;

  const head = `<thead>
    <tr class="group-row">
      <th class="match-col" rowspan="2" scope="col">对局</th>
      <th class="stage1" colspan="${stage1.length}">第一阶段 · ${stage1.length} 项</th>
      <th class="stage2" colspan="${stage2.length}">第二阶段 · ${stage2.length} 类</th>
      <th colspan="3">合计</th>
    </tr>
    <tr class="item-row">
      ${stage1.map((item) => `<th scope="col">${esc(STAGE1_SHORT[item.id] || item.label)}</th>`).join("")}
      ${stage2.map((item) => `<th scope="col">${esc(STAGE2_SHORT[item.family] || FAMILY_LABEL[item.family] || item.family)}</th>`).join("")}
      <th scope="col">扣 S1</th><th scope="col">扣 S2</th><th scope="col">综合</th>
    </tr>
  </thead>`;

  const rows = matches.map((m, index) => {
    const cells1 = stage1.map((item) => {
      const row = m.metrics.stage1Items.find((x) => x.id === item.id);
      if (!row) return '<td class="matrix-cell" title="本局缺少这一项">—</td>';
      const tip = `${row.label}：扣 ${fmt(row.points, 2)} / 上限 ${row.cap}` +
        (row.detail ? ` · ${row.detail}` : "");
      return matrixCell(row.points, row.cap, tip);
    }).join("");
    const cells2 = stage2.map((item) => {
      const row = m.breakdown.stage2.families.find((x) => x.family === item.family);
      if (!row) return '<td class="matrix-cell" title="本局缺少这一类">—</td>';
      const raw = row.raw > row.capped + 1e-9 ? ` · 原始 ${fmt(row.raw, 1)}` : "";
      const tip = `${FAMILY_LABEL[row.family] || row.family}：扣 ${fmt(row.capped, 2)} / 上限 ${row.cap}${raw}` +
        (row.detail ? ` · ${row.detail}` : "");
      return matrixCell(row.capped, row.cap, tip);
    }).join("");
    return `<tr id="match-${esc(m.id)}">
      <th scope="row" class="match-col">
        <span class="matrix-rank">${String(index + 1).padStart(2, "0")}</span>
        <span class="matrix-title">${esc(m.title)}</span>
        <span class="matrix-sub">${esc(m.date)} · ${esc(m.configuration?.map || m.map)} · ${esc(m.configuration?.ai || m.opponent)} · ${duration(m.configuration?.durationSeconds ?? m.metrics?.durationSeconds)}</span>
      </th>
      ${cells1}${cells2}
      <td class="matrix-total">${scorePair(m.breakdown.stage1.deduction, 100, 1)}</td>
      <td class="matrix-total">${scorePair(m.breakdown.stage2.deduction, 100, 1)}</td>
      <td class="matrix-grand">${scorePair(m.scores.total, 100, 2)}</td>
    </tr>`;
  }).join("");

  host.innerHTML = `<table class="matrix">
    <caption>格子里是该局该项的扣分（0 记作 ·，深色底表示达到该项上限）；鼠标悬浮可看该项在本局的证据与上限。</caption>
    ${head}<tbody>${rows}</tbody>
  </table>`;

  const hash = decodeURIComponent(window.location.hash.slice(1));
  const target = hash.startsWith("match-") ? document.getElementById(hash) : null;
  if (target) requestAnimationFrame(() => target.scrollIntoView({ block: "center" }));
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
    renderMatrix(data);
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
