/* Render the evaluation ledger from resource/results.json. */
const nf = new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 2 });
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

function renderToolCards(data) {
  const host = document.querySelector("#tool-cards");
  if (!host) return;
  const trio = data.current.filter((m) => m.group === "trio");
  host.innerHTML = trio
    .map((m) => {
      const s1 = m.metrics.stage1Items;
      const pick = (id) => (s1.find((i) => i.id === id) || {}).points ?? 0;
      return `<article class="tool-card tier-${tier(m.scores.total)}">
        <header class="tool-card-head">
          <span class="tool-tag">${esc(m.mcp ? "MCP" : "NO MCP")}</span>
          ${badge(m.result)}
        </header>
        <h3>${esc(m.title)}</h3>
        <p class="tool-card-tool">${esc(m.tool)}</p>
        <div class="tool-score"><b>${fmtTotal(m.scores.total)}</b><span>综合分</span></div>
        <dl class="tool-split">
          <div><dt>第一阶段</dt><dd>${fmt(m.scores.stage1)}</dd></div>
          <div><dt>第二阶段</dt><dd>${fmt(m.scores.stage2)}</dd></div>
          <div><dt>决策周期</dt><dd>${fmt(m.metrics.decisionCycleSeconds)}s</dd></div>
          <div><dt>生产速率扣分</dt><dd>${fmt(pick("A4-production-rate"))}/6</dd></div>
        </dl>
        <p class="tool-meta">${esc(m.date)} · ${esc(sessionShort(m.session))} · ${esc(m.result.detail)}</p>
      </article>`;
    })
    .join("");
}

function renderToolMetrics(data) {
  const table = document.querySelector("#tool-metrics");
  if (!table) return;
  const trio = data.current.filter((m) => m.group === "trio");
  const rows = [
    ["综合分 total-v1", (m) => `<b>${fmtTotal(m.scores.total)}</b>`],
    ["第一阶段 stage1-v2", (m) => fmt(m.scores.stage1)],
    ["第二阶段 stage2-v2", (m) => fmt(m.scores.stage2)],
    ["有效窗口", (m) => duration(m.metrics.durationSeconds)],
    ["结局", (m) => esc(m.result.label)],
    ["低层命令数", (m) => nf.format(m.metrics.nativeCommands)],
    ["查询 / 行动", (m) => `${m.metrics.queries} / ${m.metrics.actions}`],
    ["决策周期（去离群均值）", (m) => `${fmt(m.metrics.decisionCycleSeconds)}s`],
    ["首个行动指令", (m) => `${fmt(m.metrics.firstOrderSeconds)}s`],
    ["生产指令 / 分钟", (m) => nf.format(m.metrics.productionPerMinute)],
    ["底层命令归属缺失", (m) => `${m.metrics.attributionMissing} / ${m.metrics.nativeCommands}`],
    ["DSH 会话步数", (m) => String(m.runtime.steps)],
    ["工具总耗时", (m) => `${m.runtime.toolSeconds}s`],
    ["输出 token", (m) => nf.format(m.runtime.outputTokens)],
  ];
  table.innerHTML = `<thead><tr><th scope="col">指标</th>${trio.map((m) => `<th scope="col">${esc(m.title.split("·")[0].trim())}</th>`).join("")}</tr></thead>
    <tbody>${rows.map(([label, cell]) => `<tr><th scope="row">${esc(label)}</th>${trio.map((m) => `<td>${cell(m)}</td>`).join("")}</tr>`).join("")}</tbody>`;
}

function renderCurrent(data) {  const table = document.querySelector("#current-table");
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
    </tr>`)
    .join("");
  table.innerHTML = `<thead><tr><th scope="col">综合分</th><th scope="col">工具</th><th scope="col">模型</th><th scope="col">对局</th><th scope="col">结局</th><th scope="col">第一 / 第二阶段</th></tr></thead><tbody>${rows}</tbody>`;
}

const FAMILY_LABEL = { AMBUSH: "偷袭", GRINDER: "绞肉机", STALE: "指令过期", BUILDER_IDLE: "建造者闲置", FACTORY_IDLE: "工厂闲置", FUNDS: "资金闲置" };

function deductionCell(points, cap, raw) {
  const hit = points >= cap - 1e-9 ? ' <span class="cap-hit">封顶</span>' : "";
  const rawNote = typeof raw === "number" && raw > points + 1e-9 ? `原始 ${fmt(raw, 1)}` : "";
  return `${fmt(points, 2)}${hit}<span class="cell-sub">/ ${cap}${rawNote ? " · " + rawNote : ""}</span>`;
}

function renderDeductions(data) {
  const table = document.querySelector("#deduction-table");
  const more = document.querySelector("#deduction-more");
  if (!table) return;
  const trio = data.current.filter((m) => m.group === "trio");
  const stage1 = [];
  const seen1 = new Set();
  for (const m of trio) for (const item of m.metrics.stage1Items) if (!seen1.has(item.id)) { seen1.add(item.id); stage1.push(item); }
  const stage2 = [];
  const seen2 = new Set();
  for (const m of trio) for (const family of m.breakdown.stage2.families) if (!seen2.has(family.family)) { seen2.add(family.family); stage2.push(family); }
  const weight = (family) => Math.max(...trio.map((m) => (m.breakdown.stage2.families.find((x) => x.family === family.family) || { capped: 0 }).capped));
  stage2.sort((a, b) => weight(b) - weight(a) || b.cap - a.cap);
  const head = `<thead><tr><th scope="col">扣分项</th>${trio.map((m) => `<th scope="col">${esc(m.title.split("·")[0].trim())}</th>`).join("")}</tr></thead>`;
  const rows1 = stage1.map((item) => `<tr><th scope="row">${esc(item.label)}</th>${trio.map((m) => {
    const row = m.metrics.stage1Items.find((x) => x.id === item.id) || { points: 0, cap: item.cap };
    return `<td>${deductionCell(row.points, row.cap)}</td>`;
  }).join("")}</tr>`).join("");
  const rows2 = stage2.map((family) => `<tr><th scope="row">${esc(FAMILY_LABEL[family.family] || family.family)}</th>${trio.map((m) => {
    const row = m.breakdown.stage2.families.find((x) => x.family === family.family) || { capped: 0, cap: family.cap, raw: 0 };
    return `<td>${deductionCell(row.capped, row.cap, row.raw)}</td>`;
  }).join("")}</tr>`).join("");
  table.innerHTML = `${head}<tbody>${rows1}
    <tr class="sum-row"><th scope="row">第一阶段扣分合计</th>${trio.map((m) => `<td>${fmt(m.breakdown.stage1.deduction, 2)}</td>`).join("")}</tr>
    ${rows2}
    <tr class="sum-row"><th scope="row">第二阶段扣分合计</th>${trio.map((m) => `<td>${fmt(m.breakdown.stage2.deduction, 2)}</td>`).join("")}</tr>
    <tr class="sum-row total-row"><th scope="row">最终得分</th>${trio.map((m) => `<td><b>${fmtTotal(m.scores.total)}</b></td>`).join("")}</tr></tbody>`;
  if (more) {
    more.innerHTML = data.current.filter((m) => m.group !== "trio").map((m) => `<details class="deduction-block">
      <summary><b>${esc(m.title)}</b><span>${esc(m.date)} · ${esc(m.tool)}</span><i>${fmtTotal(m.scores.total)}</i></summary>
      <div class="deduction-grid">
        <div><h4>第一阶段 ${fmt(m.breakdown.stage1.score)} 分（扣 ${fmt(m.breakdown.stage1.deduction)}）</h4><ul>${m.metrics.stage1Items.map((i) => `<li><span>${esc(i.label)}</span><b>${fmt(i.points, 2)} / ${i.cap}</b></li>`).join("")}</ul></div>
        <div><h4>第二阶段 ${fmt(m.breakdown.stage2.score)} 分（扣 ${fmt(m.breakdown.stage2.deduction)}）</h4><ul>${m.breakdown.stage2.families.map((f) => `<li><span>${esc(FAMILY_LABEL[f.family] || f.family)}</span><b>${fmt(f.capped, 2)} / ${f.cap}</b></li>`).join("")}</ul></div>
      </div>
    </details>`).join("");
  }
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
    renderToolCards(data);
    renderToolMetrics(data);
    renderCurrent(data);
    renderDeductions(data);
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
