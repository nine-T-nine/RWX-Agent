/* Render the old_v1.1 evaluation archive from resource/results.json. */
const esc = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"})[c]);
const fmt = (v, digits = 2) => typeof v === "number" ? v.toFixed(digits) : "—";
const score = (v, digits = 2) => typeof v === "number" ? `${v.toFixed(digits)}/100` : "—";
const duration = (seconds) => {
  if (typeof seconds !== "number") return "—";
  const s = Math.round(seconds); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
};
const tier = (total) => total >= 60 ? "high" : total >= 35 ? "mid" : "low";
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

function renderKpis(data) {
  const items = [[data.overview.matches,"old_v1.1 对局","全部归档"],
    [score(data.overview.best.totalV2),"最高 total-v2",data.overview.best.title],
    [score(data.overview.average),"归档均分",`${data.overview.matches} 局`],
    [`${data.overview.wins} / ${data.overview.defeats}`,"胜 / 负","每局明确标注"]];
  document.querySelector("#results-kpis").innerHTML = items.map(([value,label,hint]) =>
    `<div class="kpi"><b>${esc(value)}</b><span>${esc(label)}</span><i>${esc(hint)}</i></div>`).join("");
}
function renderMatches(data) {
  const rows = data.matches.map((m) => `<tr>
    <td><span class="tier-dot tier-${tier(m.scores.totalV2)}"></span><b>${score(m.scores.totalV2)}</b></td>
    <td><b>${esc(m.title)}</b><span class="cell-sub">${esc(m.archive)} · ${esc(m.evidenceSource)}</span></td>
    <td>${pairs(m.pairs)}</td>
    <td><b>${esc(m.map)}</b><span class="cell-sub">${esc(m.opponent)}</span></td>
    <td><b>${duration(m.duration.scoreSeconds)}</b><span class="cell-sub">评分窗口${m.duration.gameSeconds ? ` · 游戏 ${duration(m.duration.gameSeconds)}` : ""}</span></td>
    <td>${badge(m.result)}<span class="cell-sub">${esc(m.result.detail)}</span></td>
    <td>${score(m.scores.stage1V2,1)} / ${score(m.scores.stage3,1)}<span class="cell-sub">旧 S1/S2 ${fmt(m.scores.stage1V1,1)} / ${fmt(m.scores.stage2,1)}</span></td>
  </tr>`).join("");
  document.querySelector("#current-table").innerHTML = `<thead><tr><th>total-v2</th><th>对局 / 归档</th><th>Agent ↔ 工具</th><th>地图 / 对手</th><th>时长</th><th>胜负</th><th>S1-v2 / S3</th></tr></thead><tbody>${rows}</tbody>`;
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

const menuToggle = document.querySelector(".menu-toggle"), navigation = document.querySelector("#primary-nav");
if (menuToggle && navigation) menuToggle.addEventListener("click",()=>{const open=menuToggle.getAttribute("aria-expanded")!=="true";menuToggle.setAttribute("aria-expanded",String(open));navigation.classList.toggle("open",open);});

fetch(new URL("./resource/results.json", import.meta.url)).then(r=>{if(!r.ok)throw Error();return r.json();}).then((data)=>{
  renderKpis(data); renderMatches(data); renderMatrix(data); renderIgnored(data); renderMethod(data);
  document.querySelector("#standard-line").textContent = `total-v2 = stage3 × ${data.standard.k} + stage1-v2 × ${1-data.standard.k}；参数状态 provisional。`;
  document.querySelector("#last-updated").textContent = `最后更新：${new Date(data.generatedAt).toLocaleString("zh-CN",{timeZone:"Asia/Shanghai",hour12:false})}`;
  document.documentElement.classList.add("results-ready");
}).catch(()=>document.querySelectorAll(".data-pending").forEach((el)=>{el.hidden=false;el.textContent="数据未加载，请通过 HTTP 服务打开页面。";}));
fetch(new URL("./resource/scoring.json", import.meta.url)).then(r=>r.json()).then(renderScoring);
