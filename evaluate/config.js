'use strict';

const fs = require('node:fs');
const path = require('node:path');

const FILE = path.join(__dirname, 'config.json');

function loadConfig() {
  const config = JSON.parse(fs.readFileSync(FILE, 'utf8').replace(/^\uFEFF/, ''));
  if (config.schema !== 1 || config.version !== 'rwx-evaluation-config-v1')
    throw Error(`Unsupported evaluation config: ${config.version ?? 'unknown'}`);
  return config;
}

function currentTree(node) {
  if (node == null || typeof node !== 'object' || Array.isArray(node)) return node;
  if (Object.prototype.hasOwnProperty.call(node, 'current')) return node.current;
  return Object.fromEntries(Object.entries(node)
    .filter(([key]) => !['description', 'version', 'status'].includes(key))
    .map(([key, value]) => [key, currentTree(value)]));
}

function at(config, dotted) {
  return dotted.split('.').reduce((value, key) => value?.[key], config);
}

function current(dotted, config = loadConfig()) {
  const node = at(config, dotted);
  if (node == null) throw Error(`Missing evaluation config path: ${dotted}`);
  return currentTree(node);
}

/** Archive labels: `current` is the batch just retired, `legacy` is everything archived earlier. */
function archiveLabels(config = loadConfig()) {
  const policy = config.policy || {};
  return policy.archiveLabels || {current: policy.archiveLabel || 'old_v1.1', legacy: 'old_v1.0'};
}

/** A match's label follows its folder: `_archive/<label>/…` wins, everything else is the current batch. */
function archiveLabelFor(relativePath, config = loadConfig()) {
  const labels = archiveLabels(config), known = Object.values(labels);
  const parts = String(relativePath).replace(/\\/g, '/').split('/').filter(Boolean);
  if (parts[0] === '_archive') return parts[1] && known.includes(parts[1]) ? parts[1] : labels.legacy;
  return labels.current;
}

module.exports = {FILE, loadConfig, currentTree, current, archiveLabels, archiveLabelFor};
