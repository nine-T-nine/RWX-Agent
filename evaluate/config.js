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

module.exports = {FILE, loadConfig, currentTree, current};
