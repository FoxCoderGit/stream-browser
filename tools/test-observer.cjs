const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

function fixture() {
  let listener;
  const observers = new Map(), events = [];
  const dispatcher = {sendRequest: (type, e) => events.push({type, ...e}), registerListener: l => listener = l};
  const top = {documentURI: {spec: 'https://example.test/watch'}, documentTitle: 'Example'};
  const browsingContext = {top: {currentWindowGlobal: top}};
  const context = {
    Ci: {nsIHttpChannel: {}, nsIContentPolicy: {TYPE_DOCUMENT: 6}},
    JSWINDOWACTORS: {},
    ChromeUtils: {importESModule: () => ({EventDispatcher: {instance: dispatcher}})},
    Services: {
      appinfo: {processType: 0, PROCESS_TYPE_DEFAULT: 0},
      obs: {addObserver: (o, t) => observers.set(t, o)},
      io: {newURI: u => ({host: new URL(u).hostname})},
      cookies: {getCookiesFromHost: () => [{host: '.example.test', path: '/', name: 'session', value: 'secret', isSecure: true, isHttpOnly: true, isSession: true}]},
    },
  };
  const module = fs.readFileSync(path.join(__dirname, '../gecko/stream-observer.js'), 'utf8');
  vm.runInNewContext(module.replace('export function', 'function'), context);
  function response(url = 'https://example.test/movie.mp4') {
    const channel = {URI: {spec: url}, originalURI: {spec: url}, responseStatus: 200, requestMethod: 'GET', contentType: 'video/mp4',
      loadInfo: {originAttributes: {}, browsingContext, innerWindowID: 42},
      visitRequestHeaders(visitor) { visitor.visitHeader('User-Agent', 'test'); visitor.visitHeader('Cookie', 'secret'); },
    };
    observers.get('http-on-examine-response').observe({QueryInterface: () => channel});
  }
  function query(type, url, frame) {
    let value, error;
    listener.onEvent(type, {url, frame}, {onSuccess: v => value = v, onError: e => error = e});
    return {value, error};
  }
  const windows = new Map();
  function selection(frame, source, activating) {
    if (!windows.has(frame)) windows.set(frame, {browsingContext, innerWindowId: frame});
    context.publishStreamSelection(windows.get(frame), {source, activating, revision: 1});
  }
  return {events, observers, response, query, selection};
}
test('network events exclude credentials until explicit resolution', () => {
  const f = fixture(); f.response();
  assert.equal(f.events.length, 1);
  assert.equal(f.events[0].mime, 'video/mp4');
  assert.equal(f.events[0].frame, '42');
  assert.equal(JSON.stringify(f.events).includes('secret'), false);
  const resolved = JSON.parse(f.query('Stream:Resolve', 'https://example.test/movie.mp4').value.json);
  assert.equal(resolved.cookies.length, 1);
  assert.equal(resolved.cookies[0].httpOnly, true);
  assert.equal(resolved.headers.cookie, undefined);
  assert.ok(f.query('Stream:Resolve', 'https://example.test/movie.mp4', '99').error);
});
test('bounded contexts and readiness handshake', () => {
  const f = fixture();
  assert.equal(f.query('Stream:Ping').value.version, 2);
  for (let i = 0; i < 513; i++) f.response('https://example.test/' + i);
  assert.ok(f.query('Stream:Resolve', 'https://example.test/0').error);
  assert.ok(f.query('Stream:Resolve', 'https://example.test/512').value);
  assert.equal(f.observers.size, 3);
});

test('metadata from an old iframe cannot replace the selected player', () => {
  const f = fixture();
  f.selection(42, 'https://example.test/first.mp4', true);
  f.selection(43, 'https://example.test/second.mp4', true);
  f.selection(42, 'https://example.test/first.mp4', false);
  assert.equal(f.events.length, 2);
  assert.equal(f.events.at(-1).source, 'https://example.test/second.mp4');
});

test('a reused media element clears the old source before reporting the new one', () => {
  class MediaElement {}
  class Actor {
    contentWindow = {HTMLMediaElement: MediaElement};
    events = [];
    sendAsyncMessage(name, payload) { this.events.push(payload); }
  }
  const module = fs.readFileSync(path.join(__dirname, '../gecko/StreamSelectionChild.sys.mjs'), 'utf8');
  const Child = vm.runInNewContext(module.replace('export class', 'class') + '\nStreamSelectionChild',
    {JSWindowActorChild: Actor});
  const actor = new Child();
  const old = Object.assign(new MediaElement(), {currentSrc: 'first.mp4'});
  const next = Object.assign(new MediaElement(), {currentSrc: 'second.mp4'});
  const fire = (type, target) => actor.handleEvent({type, target, isTrusted: true});
  fire('play', old);
  fire('loadedmetadata', next);
  fire('emptied', old);
  old.currentSrc = 'third.mp4';
  fire('loadedmetadata', old);
  fire('play', next);
  fire('resize', old);
  assert.deepEqual(actor.events.map(event => event.source), ['first.mp4', '', 'third.mp4', 'second.mp4']);
  assert.deepEqual(actor.events.map(event => event.revision), [1, 2, 2, 3]);
});
