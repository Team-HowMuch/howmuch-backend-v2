// Run the production reconciliation function in isolation, without DOM/network.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(__dirname + '/web/console.js', 'utf8');
const start = source.indexOf('function reconcileRowMembers(');
const end = source.indexOf('\nfunction syncDraftMembers()', start);
const context = vm.createContext({});
vm.runInContext(source.slice(start, end), context);
const reconcile = context.reconcileRowMembers;
const previous = ['A', 'B', 'C', 'D'];
// Remove the creator and a participant; saved subsets remain valid and usable.
const rows = [{creator: 'A', participants: ['A', 'B']}, {creator: 'C', participants: ['C', 'D']}];
reconcile(rows, previous, ['B', 'C', 'D'], 'B');
assert.equal(rows[0].creator, 'B');
assert.deepEqual(rows[0].participants, ['B', 'C']);
assert.deepEqual(rows[1].participants, ['C', 'D']);
// Re-adding a member exposes it in the group without changing explicit subsets.
reconcile(rows, ['B', 'C', 'D'], previous, 'B');
assert.deepEqual(rows[0].participants, ['B', 'C']);
// Implicit whole-group rows follow previous membership, excluding removed names.
const implicit = [{}];
reconcile(implicit, previous, ['C', 'D'], 'D');
assert.equal(implicit[0].creator, 'D');
assert.deepEqual(implicit[0].participants, ['C', 'D']);
// Zero/one member drafts do not retain stale creators; validation blocks save.
reconcile(rows, previous, [], '');
assert.equal(rows[0].creator, undefined);
assert.equal(rows[0].participants.length, 0);
assert.match(source, /setupPeople\('members',draft\.members,'recipe-owner',syncDraftMembers\)/);
assert.match(source, /\$\('select-people'\)\.onclick=.*syncDraftMembers\(\)/);
console.log('Member removal/addition/creator fallback/subset/empty-group checks passed');
