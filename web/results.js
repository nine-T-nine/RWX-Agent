/* Render the v2 ledger (current) and the frozen v1 snapshot from resource/results.json. */
const esc = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"})[c]);
const fmt = (v, digits = 2) => typeof v === "number" ? v.toFixed(digits) : "—";
const score = (v, digits = 2) => typeof v === "number" ? `${v.toFixed(digits)}/100` : "—";
const duration = (seconds) => {
  if (typeof seconds !== "number") return "—";
  const s = Math.round(seconds); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
};
const tier = (total) => typeof total === "number" ? (total >= 60 ? "high" : total >= 35 ? "mid" : "low") : "low";
const FAMILY = {STAGE3_AMBUSH:"偷袭延迟",STAGE3_BARRAGE:"偷家延迟",STAGE3_GRINDER:"危险区建造者",STAGE3_FUNDS:"资金空余"};

function badge(result) {
  const cls = result.outcome === "win" ? "badge-win" : result.outcome === "defeat" ? "badge-defeat" : "";
  return `<span class="badge ${cls}">${esc(result.label || result.outcome)}</span>`;
}
function pairs(rows) {
  return rows.map((pair) => {
    const count = pair.count > 1 ? ` ×${pair.count}` : "";
    const version = pair.tool.version ? ` · ${pair.tool.version}` : "";
    return `<div class="pair"><b>${esc(pair.agent.model)}${count}</b><span class="cell-sub">${esc(pair.agent.provider)} · ${esc(pair.agent.reasoning)}</span>` +
      `<span class="cell-sub">↔ ${esc(pair.tool.name)}${esc(version)}</span>` +
      (pair.tool.extra ? `<span class="cell-sub">extra: ${esc(pair.tool.extra)}</span>` : "") + `</div>`;
  }).join("");
}
const agents = (rows) => rows.map((pair) => `${pair.agent.model}${pair.count > 1 ? ` ×${pair.count}` : ""}`).join(" + ");

function renderKpis(data) {
  const split = Object.entries(data.overview.archives || {})
    .sort(([a], [b]) => a.localeCompare(b)).map(([label, count]) => `${label} ${count}`).join(" · ");
  const items = [[data.overview.matches,"归档对局",split || "—"],
    [score(data.overview.best.totalV2),"最高 total-v2",data.overview.best.title],
    [score(data.overview.average),"归档均分",`${data.overview.matches} 局`],
    [`${data.overview.wins} / ${data.overview.defeats}`,"胜 / 负","每局明确标注"]];
  document.querySelector("#results-kpis").innerHTML = items.map(([value,label,hint]) =>
    `<div class="kpi"><b>${esc(value)}</b><span>${esc(label)}</span><i>${esc(hint)}</i></div>`).join("");
  document.querySelector("#archive-split").textContent = split;
}
function renderMatches(data) {
  const rows = data.matches.map((m) => `<tr>
    <td><span class="tier-dot tier-${tier(m.scores.totalV2)}"></span><b>${score(m.scores.totalV2)}</b></td>
    <td><b>${esc(m.title)}</b><span class="cell-sub">${esc(m.archive)} · ${esc(m.evidenceSource)}</span></td>
    <td>${pairs(m.pairs)}</td>
    <td><b>${esc(m.map)}</b><span class="cell-sub">${esc(m.opponent)}</span></td>
    <td><b>${duration(m.duration.scoreSeconds)}</b><span class="cell-sub">评分窗口${m.duration.gameSeconds ? ` · 游戏 ${duration(m.duration.gameSeconds)}` : ""}</span></td>
    <td>${badge(m.result)}<span class="cell-sub">${esc(m.result.detail)}</span></td>
    <td>${score(m.scores.stage1V2,1)}<span class="cell-sub">Stage3 ${score(m.scores.stage3,1)}</span></td>
  </tr>`).join("");
  document.querySelector("#current-table").innerHTML = `<thead><tr><th>total-v2</th><th>对局 / 归档</th><th>Agent ↔ 工具</th><th>地图 / 对手</th><th>时长</th><th>胜负</th><th>S1-v2</th></tr></thead><tbody>${rows}</tbody>`;
}
function renderV1(data) {
  const v1 = data.v1, rows = [...data.matches].sort((a, b) =>
    (b.scores.totalV1 ?? -1) - (a.scores.totalV1 ?? -1) || b.scores.totalV2 - a.scores.totalV2);
  document.querySelector("#v1-note").textContent =
    `${v1.note} 可复算 ${v1.counts.scored} 局，缺材料 ${v1.counts.missing} 局。`;
  document.querySelector("#v1-table").innerHTML = `<thead><tr><th>total-v1</th><th>对局 / 归档</th><th>Agent</th><th>时长</th><th>胜负</th><th>S1-v1 / S2</th></tr></thead><tbody>${rows.map((m) => {
    const has = typeof m.scores.totalV1 === "number";
    return `<tr>
      <td><span class="tier-dot tier-${tier(m.scores.totalV1)}"></span><b>${has ? fmt(m.scores.totalV1) : "未复算"}</b></td>
      <td><b>${esc(m.title)}</b><span class="cell-sub">${esc(m.archive)}</span></td>
      <td>${esc(agents(m.pairs))}</td>
      <td>${duration(m.duration.scoreSeconds)}</td>
      <td>${badge(m.result)}</td>
      <td>${has ? `${fmt(m.scores.stage1V1,1)} / ${fmt(m.scores.stage2,1)}` : '<span class="cell-sub">缺 stage_1 或 stage_2 报告</span>'}</td>
    </tr>`;
  }).join("")}</tbody>`;
}
function renderV1Rules(data) {
  const v1 = data.v1, capRows = v1.stage1.caps.map((cap, index) =>
    `<tr><th>${esc(v1.stage1.names[index] ?? `第 ${index + 1} 项`)}</th><td><b>${cap}</b></td></tr>`).join("");
  const families = Object.entries(v1.stage2.caps).map(([name, cap]) =>
    `<tr><th>${esc(name)}</th><td><b>${cap}</b></td></tr>`).join("");
  document.querySelector("#v1-rules-body").innerHTML =
    `<article class="scoring-stage"><header class="scoring-stage-head"><div><span>V1 / ${esc(v1.stage1.label)}</span><h3>第一阶段 12 项</h3></div>` +
    `<p>版本 ${esc(v1.version)}，权重 ${v1.weights.stage1}；2026-11-06 冻结。</p></header>` +
    `<div class="table-wrap scoring-table-wrap"><table class="data-table scoring-table"><thead><tr><th>扣分项</th><th>上限</th></tr></thead>` +
    `<tbody>${capRows}</tbody></table></div></article>` +
    `<article class="scoring-stage"><header class="scoring-stage-head"><div><span>V1 / ${esc(v1.stage2.label)}</span><h3>第二阶段 6 类</h3></div>` +
    `<p>权重 ${v1.weights.stage2}；类别内先累加再按上限封顶。</p></header>` +
    `<div class="table-wrap scoring-table-wrap"><table class="data-table scoring-table"><thead><tr><th>类别</th><th>上限</th></tr></thead>` +
    `<tbody>${families}</tbody></table></div></article>`;
}
function matrixCell(row, family) {
  const item = row.stage3.deductions[family];
  if (!item) return '<td class="matrix-cell">—</td>';
  const capped = item.deduction >= item.cap - 1e-9;
  return `<td class="matrix-cell${item.deduction ? " is-hit" : ""}${capped ? " is-capped" : ""}" title="${esc(`${FAMILY[family]}：${item.records} 条记录`)}">${fmt(item.deduction)}/${item.cap}</td>`;
}
function renderMatrix(data) {
  const families = Object.keys(FAMILY);
  const rows = data.matches.map((m, index) => `<tr id="match-${m.id}"><th scope="row" class="match-col"><span class="matrix-rank">${String(index + 1).padStart(2,"0")}</span><span class="matrix-title">${esc(m.title)}</span><span class="matrix-sub">${duration(m.duration.scoreSeconds)} · ${esc(m.result.label)}</span></th>` +
    families.map((family) => matrixCell(m, family)).join("") +
    `<td class="matrix-total">${fmt(m.scores.stage1V2,1)}</td><td class="matrix-total">${fmt(m.scores.stage3,1)}</td><td class="matrix-grand">${fmt(m.scores.totalV2,2)}</td></tr>`).join("");
  document.querySelector("#match-matrix").innerHTML = `<table class="matrix"><caption>Stage3 四项均显示“扣分/上限”；末三列为阶段与总分。</caption><thead><tr><th class="match-col">对局</th>${families.map((f)=>`<th>${FAMILY[f]}</th>`).join("")}<th>S1-v2</th><th>Stage3</th><th>total-v2</th></tr></thead><tbody>${rows}</tbody></table>`;
}
function renderIgnored(data) {
  const rows = (data.ignored || []).map((item) => `<article class="gap"><h3>${esc(item.title)}</h3>` +
    `<p class="gap-date">${esc(item.reason)}</p><p>${esc(item.note)}</p>` +
    `<p class="gap-impact">${esc(item.relativePath)}</p></article>`).join("");
  document.querySelector("#gap-list").innerHTML = rows || "<p>没有材料不足的对局。</p>";
}
function renderMethod(data) {
  document.querySelector("#method-sources").innerHTML = data.method.sources.map((v)=>`<li>${esc(v)}</li>`).join("");
  document.querySelector("#method-limits").innerHTML = data.method.limits.map((v)=>`<li>${esc(v)}</li>`).join("");
}
function scoringTable(items, caption) {
  return `<div class="table-wrap scoring-table-wrap"><table class="data-table scoring-table"><caption>${esc(caption)}</caption><thead><tr><th>扣分项</th><th>上限</th><th>评分公式 / 参数</th><th>说明</th></tr></thead><tbody>${items.map((item)=>`<tr><th>${esc(item.name)}</th><td><b>${esc(item.cap)}</b></td><td class="formula-cell">${esc(item.formula)}</td><td>${esc(item.description)}</td></tr>`).join("")}</tbody></table></div>`;
}
function renderScoring(data) {
  document.querySelector("#full-match-scoring").innerHTML = data.fullMatch.stages.map((stage)=>`<article class="scoring-stage"><header class="scoring-stage-head"><div><span>${esc(stage.label)} / ${esc(stage.version)}</span><h3>${esc(stage.title)}</h3></div><p>${esc(stage.summary)}</p></header>${scoringTable(stage.items,`${stage.label}，共 ${stage.items.length} 项`)}</article>`).join("");
  document.querySelector("#micro-cases-list").innerHTML = data.microCases.map((item)=>`<article class="micro-case" id="${esc(item.id)}"><div class="case-overview"><figure class="case-figure"><img src="${esc(item.image)}" alt="${esc(item.imageAlt)}" width="1536" height="864" loading="lazy"><figcaption>${esc(item.imageCaption)}</figcaption></figure><div class="case-copy"><span class="case-label">${esc(item.label)} / ${esc(item.version)}</span><h3>${esc(item.title)}</h3><p>${esc(item.environment)}</p></div></div>${scoringTable(item.items,`${item.label} 扣分项`)}</article>`).join("");
}

/* --- version switch ------------------------------------------------------------------------- */
function selectVersion(version) {
  document.querySelectorAll("[data-version-tab]").forEach((tab) => {
    const on = tab.dataset.versionTab === version;
    tab.classList.toggle("is-active", on);
    tab.setAttribute("aria-selected", String(on));
  });
  document.querySelectorAll("[data-version-panel]").forEach((panel) => {
    panel.hidden = panel.dataset.versionPanel !== version;
  });
}
function revealHash() {
  const id = decodeURIComponent(location.hash.replace(/^#/, ""));
  const target = id ? document.getElementById(id) : null;
  if (!target) return;
  const panel = target.closest("[data-version-panel]");
  if (!panel) return;
  selectVersion(panel.dataset.versionPanel);
  requestAnimationFrame(() => target.scrollIntoView({block: "start"}));
}
document.querySelectorAll("[data-version-tab]").forEach((tab) => tab.addEventListener("click", () => {
  selectVersion(tab.dataset.versionTab);
  history.replaceState(null, "", tab.dataset.versionTab === "v1" ? "#v1-table-section" : "#current");
}));
window.addEventListener("hashchange", revealHash);

const menuToggle = document.querySelector(".menu-toggle"), navigation = document.querySelector("#primary-nav");
if (menuToggle && navigation) menuToggle.addEventListener("click",()=>{const open=menuToggle.getAttribute("aria-expanded")!=="true";menuToggle.setAttribute("aria-expanded",String(open));navigation.classList.toggle("open",open);});

fetch(new URL("./resource/results.json", import.meta.url)).then(r=>{if(!r.ok)throw Error();return r.json();}).then((data)=>{
  renderKpis(data); renderMatches(data); renderMatrix(data); renderV1(data); renderV1Rules(data);
  renderIgnored(data); renderMethod(data); revealHash();
  document.querySelector("#standard-line").textContent =
    `total-v2 = stage3 × ${data.standard.k} + stage1-v2 × ${1 - data.standard.k}；参数状态 provisional。` +
    ` v1（${data.v1.formula}）已冻结，不再更新。`;
  document.querySelector("#last-updated").textContent = `最后更新：${new Date(data.generatedAt).toLocaleString("zh-CN",{timeZone:"Asia/Shanghai",hour12:false})}`;
  document.documentElement.classList.add("results-ready");
}).catch(()=>document.querySelectorAll(".data-pending").forEach((el)=>{el.hidden=false;el.textContent="数据未加载，请通过 HTTP 服务打开页面。";}));
fetch(new URL("./resource/scoring.json", import.meta.url)).then(r=>r.json()).then(renderScoring);
